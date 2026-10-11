#!/usr/bin/env bb
;; tvremote — a phone remote for Netflix, YouTube and reading on a TV computer.
;;
;; Runs ON the computer plugged into the TV. Serves a phone page (port 7700 by
;; default) and drives a dedicated Chrome (its own profile, so Chrome allows
;; remote control) over the Chrome DevTools Protocol on 127.0.0.1. Settings are
;; in config.edn beside this file (see config.example.edn). Nothing fakes keystrokes at the desktop
;; level: play/pause/seek go through Netflix's own player API inside the tab,
;; browsing reads the Netflix page itself. See TVREMOTE.md.

(ns tvremote
  (:require [babashka.http-client :as http]
            [babashka.http-client.websocket :as ws]
            [babashka.process :as p]
            [cheshire.core :as json]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as server]))

;; ---------------------------------------------------------------- Settings
;; config.edn beside this file; every key is optional (config.example.edn).

(def base-dir
  "The folder this file is in: config.edn, state.edn and Chrome's logs live here."
  (-> *file* io/file .getAbsoluteFile .getParent))

(def home (System/getenv "HOME"))

(def config
  (merge {:port 7700 :cdp-port 9222 :people [] :chrome nil :chrome-flags [] :profile-dir nil}
         (try (edn/read-string (slurp (str base-dir "/config.edn"))) (catch Exception _ {}))))

(def port (parse-long (or (System/getenv "TVREMOTE_PORT") (str (:port config)))))
(def cdp-port (:cdp-port config))

(def chrome-cmd
  "How to start Chrome: the setting, else google-chrome if installed, else the Flatpak."
  (or (:chrome config)
      (if (fs/which "google-chrome") ["google-chrome"] ["flatpak" "run" "com.google.Chrome"])))

(def profile-dir
  "The remote's own Chrome profile (Chrome refuses remote control of the default
   one). The Flatpak can only write under its own folder, so it goes there."
  (or (:profile-dir config)
      (if (= "flatpak" (first chrome-cmd))
        (str home "/.var/app/com.google.Chrome/config/tvremote-profile")
        (str home "/.config/tvremote-chrome-profile"))))

(def runtime-dir
  "The desktop session's runtime folder (/run/user/<uid>), for reaching its
   display and D-Bus from a service."
  (or (System/getenv "XDG_RUNTIME_DIR")
      (str "/run/user/" (str/trim (:out (p/shell {:out :string} "id" "-u"))))))
(def nf "https://www.netflix.com")

;; The last page read, kept on disk so a fresh Chrome (or a restarted server)
;; gets it back.
(def state-file (str base-dir "/state.edn"))

(defn load-state [] (try (read-string (slurp state-file)) (catch Exception _ {})))

(def state-lock (Object.))

(defn update-state!
  "The one way state.edn is changed: (f state), under a lock, written to a
   temporary file and renamed over the old one. Two saves at once used to
   lose one of them, and a save read mid-write by another could wipe the
   whole file (falsifier, 2026-10-11). A file that exists but won't read
   throws here rather than being overwritten with {}."
  [f]
  (locking state-lock
    (let [st  (if (fs/exists? state-file) (read-string (slurp state-file)) {})
          tmp (str state-file ".tmp")]
      (spit tmp (pr-str (f st)))
      (fs/move tmp state-file {:replace-existing true :atomic-move true})
      nil)))

(defn save-reading! [url] (update-state! #(assoc % :reading url)))

;; ---------------------------------------------------------------- CDP

(defn targets
  "Chrome's open targets, or nil when Chrome isn't listening."
  []
  (try
    (let [r (http/get (str "http://127.0.0.1:" cdp-port "/json/list") {:throw false})]
      (when (= 200 (:status r)) (json/parse-string (:body r) true)))
    (catch Exception _ nil)))

(defn chrome-up? [] (some? (targets)))

(defn on-site?
  "Is target t a page actually ON site (by host, e.g. \"netflix.com\")? Not a
   substring test: Google's sign-in page carries youtube.com in its continue=
   parameter, and running script on that page got the sign-in revoked."
  [site t]
  (and (= "page" (:type t))
       (let [h (try (.getHost (java.net.URI. (:url t))) (catch Exception _ nil))]
         (boolean (and h (or (= h site) (str/ends-with? h (str "." site))))))))

(defn site-tab
  "The tab on site, opened (in the background) if there isn't one. Never any
   other page: script must only ever run on Netflix or YouTube themselves."
  [site url]
  (let [find #(first (filter (partial on-site? site) (targets)))]
    (cond
      (not (chrome-up?)) nil ; Start asks for a tab before Chrome exists
      (find) (find)
      :else (do (http/put (str "http://127.0.0.1:" cdp-port "/json/new?"
                               (java.net.URLEncoder/encode url "UTF-8")) {:throw false})
                (Thread/sleep 1500)
                (find)))))

(defn tv-target
  "The Netflix tab."
  []
  (site-tab "netflix.com" "https://www.netflix.com/browse"))

(defn cdp
  "One CDP call on a fresh connection to target t. Returns the :result map,
   throws on a CDP error. Messages can arrive in fragments, hence buf."
  ([t method] (cdp t method {}))
  ([t method params]
   (let [res (promise)
         buf (StringBuilder.)
         sock (ws/websocket
               {:uri (:webSocketDebuggerUrl t)
                :on-message (fn [_ data last?]
                              (.append buf (str data))
                              (when last?
                                (let [m (json/parse-string (str buf) true)]
                                  (.setLength buf 0)
                                  (when (= 1 (:id m)) (deliver res m)))))
                :on-error (fn [_ e] (deliver res {:error {:message (str e)}}))})]
     (try
       (ws/send! sock (json/generate-string {:id 1 :method method :params params}))
       (let [m (deref res 20000 {:error {:message (str method " timed out")}})]
         (when-let [e (:error m)] (throw (ex-info (:message e) {:cdp e})))
         (:result m))
       (finally (try (ws/close! sock) (catch Exception _)))))))

(def sign-in-hosts
  "Google's sign-in pages. Script run on one once got the TV's sign-in revoked,
   so js refuses them, whichever way a tab got there (a link followed from
   Reading, a redirect), falsifier 2026-10-11."
  #{"accounts.google.com" "accounts.youtube.com"})

(defn sign-in-page?
  "Is tab t on a Google sign-in page right now? Asks Chrome, as t's own :url
   can be from before a redirect."
  [t]
  (let [now (or (first (filter #(= (:id t) (:id %)) (targets))) t)]
    (boolean (some #(on-site? % now) sign-in-hosts))))

(defn js
  "Evaluate expr in tab t (as a user gesture, so play() is allowed) and return
   its value. Never on a Google sign-in page."
  [t expr]
  (when (sign-in-page? t)
    (throw (ex-info "The TV is on Google's sign-in page - the remote leaves it alone" {})))
  (let [r (cdp t "Runtime.evaluate" {:expression expr :returnByValue true
                                     :awaitPromise true :userGesture true})]
    (when-let [ex (:exceptionDetails r)]
      (throw (ex-info (or (get-in ex [:exception :description]) (:text ex)) {})))
    (get-in r [:result :value])))

(def keys-map
  {"space" {:key " " :code "Space" :windowsVirtualKeyCode 32}
   "f"     {:key "f" :code "KeyF" :windowsVirtualKeyCode 70}
   "esc"   {:key "Escape" :code "Escape" :windowsVirtualKeyCode 27}})

(defn press
  "A real (trusted) key press in the tab — what fullscreen needs."
  [t k]
  (let [m (keys-map k)]
    (cdp t "Page.bringToFront")
    (cdp t "Input.dispatchKeyEvent" (assoc m :type "keyDown" :text (when (= 1 (count (:key m))) (:key m))))
    (cdp t "Input.dispatchKeyEvent" (assoc m :type "keyUp"))))

(defn wait-for
  "Poll expr in tab t until truthy or timeout-ms; returns the value or nil."
  [t expr timeout-ms]
  (let [end (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v (try (js t expr) (catch Exception _ nil))]
        (cond v v
              (> (System/currentTimeMillis) end) nil
              :else (do (Thread/sleep 300) (recur)))))))

;; ---------------------------------------------------------------- Chrome

(defn other-chromes
  "Pids of Chrome browser processes (the Flatpak's /app/extra/chrome or a
   native /opt/google/chrome/chrome, not their helpers: those carry --type=).
   Only asked while the remote's own Chrome is down, so every one found is
   someone else's, e.g. the normal Chrome of whoever uses this computer."
  []
  (->> (str/split-lines (:out (p/shell {:out :string :continue true} "ps" "-u" (System/getenv "USER") "-o" "pid=,args=")))
       (map str/trim)
       (keep #(let [[_ pid args] (re-matches #"(\d+) (.*)" %)]
                (when (and args (re-find #"^(/app/extra/chrome|/opt/google/chrome/chrome) " args)
                           (not (str/includes? args "--type=")))
                  pid)))))

(defn close-other-chromes!
  "SIGTERM is Chrome's orderly shutdown: it saves the session and records a
   clean exit, so no 'Restore pages?' next time. Waits up to 15s for them to go."
  []
  (let [pids (other-chromes)]
    (doseq [pid pids] (p/shell {:continue true} "kill" "-TERM" pid))
    (loop [n 0]
      (when (and (seq (other-chromes)) (< n 30)) (Thread/sleep 500) (recur (inc n))))
    {:ok (empty? (other-chromes)) :closed (count pids)}))

(defn mark-clean-exit!
  "Chrome shows a 'Restore pages?' bubble after an unclean exit (power off,
   logout). It's browser UI, out of the remote's reach, so before each start
   record the last session as a normal exit."
  []
  (let [f (java.io.File. (str profile-dir "/Default/Preferences"))]
    (when (.exists f)
      (try (let [p (json/parse-string (slurp f))]
             (when (not= "Normal" (get-in p ["profile" "exit_type"]))
               (spit f (json/generate-string
                        (-> p (assoc-in ["profile" "exit_type"] "Normal")
                              (assoc-in ["profile" "exited_cleanly"] true))))))
           (catch Exception _ nil)))))

(defn name-profile!
  "Call the remote's Chrome profile \"TV\" (shown on its profile button), so
   nobody takes it for their own browser. Written while Chrome is down, as
   Chrome rewrites these files from memory while it runs."
  []
  (let [ls (java.io.File. (str profile-dir "/Local State"))
        pf (java.io.File. (str profile-dir "/Default/Preferences"))]
    (try (when (.exists ls)
           (spit ls (json/generate-string
                     (-> (json/parse-string (slurp ls))
                         (assoc-in ["profile" "info_cache" "Default" "name"] "TV")
                         (assoc-in ["profile" "info_cache" "Default" "is_using_default_name"] false)))))
         (when (.exists pf)
           (spit pf (json/generate-string (assoc-in (json/parse-string (slurp pf)) ["profile" "name"] "TV"))))
         (catch Exception _ nil))))

(defn launch-chrome! []
  (mark-clean-exit!)
  (name-profile!)
  (apply p/process
         {:out (java.io.File. (str base-dir "/chrome.log"))
          :err (java.io.File. (str base-dir "/chrome.err"))
          :extra-env {"XDG_RUNTIME_DIR" runtime-dir
                      "WAYLAND_DISPLAY" (or (System/getenv "WAYLAND_DISPLAY") "wayland-0")
                      "DISPLAY" (or (System/getenv "DISPLAY") ":0")
                      "DBUS_SESSION_BUS_ADDRESS" (str "unix:path=" runtime-dir "/bus")}}
         (concat ["setsid"] chrome-cmd
                 [(str "--user-data-dir=" profile-dir)
                  (str "--remote-debugging-port=" cdp-port)]
                 (:chrome-flags config)
                 ["--no-first-run" "--no-default-browser-check"
                  ;; dark, so it doesn't look like anyone's everyday Chrome
                  "--force-dark-mode"
                  "--hide-crash-restore-bubble"
                  "--autoplay-policy=no-user-gesture-required"
                  "--start-fullscreen"
                  (str nf "/browse")]))
  (loop [n 0]
    (cond (chrome-up?) true
          (> n 40) false
          :else (do (Thread/sleep 500) (recur (inc n))))))

;; ---------------------------------------------------------------- Netflix

;; The playing session's player, via Netflix's own player API (seeking through
;; the <video> element trips Netflix's M7375 error; its API doesn't).
(def player-js
  "(()=>{try{const vp=netflix.appContext.state.playerApp.getAPI().videoPlayer;
   const ids=vp.getAllPlayerSessionIds();
   const sid=ids.find(s=>s.startsWith('watch'))||ids[ids.length-1];
   return sid?vp.getVideoPlayerBySessionId(sid):null}catch(e){return null}})()")

(defn player-do [t body]
  (js t (str "(()=>{const p=" player-js ";const v=document.querySelector('video');" body "})()")))

;; Netflix's title element (shown only while its controls are) holds the
;; show, episode number and episode name as separate parts with no spaces.
(def nf-title-js
  "(()=>{const t=document.querySelector('[data-uia=\"video-title\"]');if(!t)return null;
   return [...t.children].map(c=>c.textContent.trim()).filter(Boolean).join(' · ')
          ||t.innerText.replace(/\\n+/g,' · ')})()")

;; The show and episode from the player's own data, which is there whether or
;; not the controls are showing: the title element above isn't, so an episode
;; Netflix moved on to by itself went unrecorded (falsifier, 2026-10-11).
(def nf-meta-js
  (str "(()=>{try{const p=" player-js ";if(!p)return null;const mid=p.getMovieId();
   const m=netflix.appContext.state.playerApp.getState().videoPlayer.videoMetadata[mid];
   const v=m&&m._metadataObject&&m._metadataObject.video;if(!v||!v.title)return null;
   const r={show:v.title,title:v.title,episode:null};
   (v.seasons||[]).forEach(s=>(s.episodes||[]).forEach(e=>{if(e.id===mid||e.episodeId===mid){
     r.episode='S'+s.seq+' E'+e.seq;r.title=v.title+' · E'+e.seq+(e.title?' · '+e.title:'')}}));
   return r}catch(e){return null}})()"))

(def status-js
  (str "const base={url:location.href,title:" nf-title-js "};
   let ep=null;try{const s=netflix.falcorCache.videos[p.getMovieId()].summary.value;
     if(s.type==='episode')ep='S'+s.season+' E'+s.episode}catch(e){}
   if(p)return Object.assign(base,{episode:ep,playing:!p.isPaused(),time:p.getCurrentTime(),
     duration:p.getDuration(),volume:p.getVolume()});
   if(v)return Object.assign(base,{playing:!v.paused});
   return base;"))

(def picked
  "The title last chosen on the phone — the watch page itself doesn't name the show."
  (atom nil))

(defn toggle [t]
  (player-do t "if(p){p.isPaused()?p.play():p.pause();return true}
                if(v){v.paused?v.play():v.pause();return true}return false"))

(defn seek [t ms]
  (player-do t (str "if(!p)return false;p.seek(Math.max(0,p.getCurrentTime()+(" ms ")));return true")))

(defn volume [t d]
  (player-do t (str "if(p){p.setVolume(Math.min(1,Math.max(0,p.getVolume()+(" d "))));return true}
                     if(v){v.volume=Math.min(1,Math.max(0,v.volume+(" d ")));return true}return false")))

(defn skip [t]
  (js t "(()=>{const b=document.querySelector('[data-uia^=\"player-skip\"],[data-uia*=\"next-episode\"]');
         if(b){b.click();return true}return false})()"))

(defn navigate [t url]
  (cdp t "Page.bringToFront")
  (cdp t "Page.navigate" {:url url}))

;; Titles are links: /watch/<id> on older pages, ?jbv=<id> (the "more info"
;; card) on the 2026 design, where the link itself carries the title in
;; aria-label and the poster as its <img>. Grouping by row (carousel section, or
;; the older .lolomoRow) gives the browse page's rows; a search page has none,
;; so it comes back as one group.
(def scrape-js
  "(()=>{
   const idOf=a=>{const m=(a.getAttribute('href')||'').match(/(?:\\/watch\\/|[?&]jbv=)(\\d+)/);return m?m[1]:null};
   const card=a=>{const id=idOf(a);if(!id)return null;
     const box=a.closest('.title-card,.slider-item,.title-card-container')||a;
     const img=a.querySelector('img')||box.querySelector('img');
     const title=a.getAttribute('aria-label')||(img&&img.alt)||'';
     return title?{id:id,title:title,img:img?img.src:null}:null};
   const grab=root=>{const s=new Set(),out=[];
     root.querySelectorAll('a[href*=\"/watch/\"],a[href*=\"jbv=\"]').forEach(a=>{const c=card(a);
       if(c&&!s.has(c.id)){s.add(c.id);out.push(c)}});return out};
   const rows=[...document.querySelectorAll('[data-uia^=\"carousel-row-section\"],.lolomoRow')];
   if(rows.length)return rows.map(r=>{const h=r.querySelector('h2,.row-header-title');
     return {title:h?h.innerText.trim():'',items:grab(r)}}).filter(r=>r.items.length);
   const all=grab(document);return all.length?[{title:'Results',items:all}]:[]})()")

;; The Shows/Movies pages carry a hidden genre menu: links with ?bc=<page>.
;; Hidden, so textContent, not innerText.
(def genres-js
  "(()=>[...document.querySelectorAll('a[href*=\"/browse/genre/\"][href*=\"bc=\"]')]
     .map(a=>({name:a.textContent.trim(),id:(a.getAttribute('href').match(/genre\\/(\\d+)/)||[])[1]}))
     .filter(g=>g.name&&g.id))()")

(def row-count-js
  "document.querySelectorAll('[data-uia^=\"carousel-row-section\"],.lolomoRow').length")

(defn scroll-all
  "Netflix adds rows as the page is scrolled. Keep going to the bottom until
   three scrolls in a row add nothing (or 40 scrolls)."
  [t]
  (loop [n 0 last -1 still 0]
    (when (and (< n 40) (< still 3))
      (js t "window.scrollTo(0,document.body.scrollHeight)")
      (Thread/sleep 700)
      (let [c (js t row-count-js)]
        (recur (inc n) c (if (= c last) (inc still) 0)))))
  (js t "window.scrollTo(0,0)"))

(def profiles-js
  "(()=>[...document.querySelectorAll('[data-uia^=\"profile-selector+tile\"]')]
     .map((a,i)=>({i:i,name:a.innerText.trim()})).filter(x=>x.name))()")

(def kind-js
  "What the tab is showing, or null while it is still loading."
  "(()=>{if(document.querySelector('[data-uia^=\"profile-selector+tile\"]'))return 'profiles';
   if(location.pathname.startsWith('/login'))return 'login';
   if(document.querySelector('.lolomoRow,[data-uia^=\"carousel-row-section\"],a[href*=\"/watch/\"],a[href*=\"jbv=\"]'))return 'titles';
   return null})()")

(defn browse
  "Read titles off url in the TV tab. Scrolls so the lazy rows render."
  [url]
  (let [t (tv-target)]
    (navigate t url)
    (Thread/sleep 800)
    (case (wait-for t kind-js 15000)
      "profiles" {:kind "profiles" :profiles (js t profiles-js)}
      "login" {:kind "login"}
      "titles" (do (scroll-all t)
                   {:kind "titles" :genres (js t genres-js) :rows (sort-by #(not (str/starts-with? (:title %) "Continue"))
                                         (js t scrape-js))})
      {:kind "nothing" :url (js t "location.href")})))

(defn pick-profile [i]
  (let [t (tv-target)]
    (js t (str "(()=>{const a=[...document.querySelectorAll('[data-uia^=\"profile-selector+tile\"]')][" i "];
               if(a){a.click();return true}return false})()"))
    (Thread/sleep 2500)
    (browse (str nf "/browse"))))

;; ---------------------------------------------------------------- YouTube
;; YouTube lives in its own tab of the same Chrome. Unlike Netflix it lets the
;; page drive its player directly: #movie_player has playVideo/seekTo/etc.

(def yt "https://www.youtube.com")

(def service
  "Which service the transport buttons act on: \"nf\" or \"yt\". Set by
   whatever the phone last browsed, played or switched to."
  (atom "nf"))

(def rv-id
  "The reading-video tab's target id: YouTube videos started from a page being
   read play there, so they never move the main YouTube tab or what it
   remembers. Kept in state.edn, so a restarted server still knows the tab."
  (atom nil))

(defn save-rv-id! [id] (reset! rv-id id) (update-state! #(assoc % :rv-id id)))

(defn main-yt?
  "A YouTube tab that isn't the reading-video one."
  [t]
  (and (on-site? "youtube.com" t) (not= (:id t) @rv-id)))

(defn yt-target
  "The main YouTube tab, opened (in the background) if there isn't one."
  []
  (let [find #(first (filter main-yt? (targets)))]
    (cond
      (not (chrome-up?)) nil
      (find) (find)
      :else (do (http/put (str "http://127.0.0.1:" cdp-port "/json/new?"
                               (java.net.URLEncoder/encode (str yt "/") "UTF-8")) {:throw false})
                (Thread/sleep 1500)
                (find)))))

(defn rv-target
  "The reading-video tab, or nil when there isn't one."
  []
  (when-let [id @rv-id] (first (filter #(= id (:id %)) (targets)))))

(defn yt-do [t body]
  (js t (str "(()=>{const p=document.getElementById('movie_player');
              const ok=p&&p.getPlayerState&&location.pathname==='/watch';" body "})()")))

(def yt-status-js
  "const base={url:location.href};if(!ok)return base;
   const d=p.getVideoData();
   return Object.assign(base,{title:d.title,picked:d.author,playing:p.getPlayerState()===1,
     time:p.getCurrentTime()*1000,duration:p.getDuration()*1000,volume:p.getVolume()/100,
     ad:!!document.querySelector('.ad-showing')});")

;; State 3 is buffering — straight after a seek — and counts as playing.
(defn yt-toggle [t] (yt-do t "if(!ok)return false;[1,3].includes(p.getPlayerState())?p.pauseVideo():p.playVideo();return true"))
(defn yt-pause [t] (yt-do t "if(ok&&[1,3].includes(p.getPlayerState()))p.pauseVideo();return true"))
(defn yt-seek [t ms] (yt-do t (str "if(!ok)return false;p.seekTo(Math.max(0,p.getCurrentTime()+(" ms ")/1000),true);return true")))
(defn yt-volume [t d]
  (yt-do t (str "if(!ok)return false;p.unMute();p.setVolume(Math.min(100,Math.max(0,p.getVolume()+(" d ")*100)));return true")))
(defn yt-skip [t]
  (js t "(()=>{const b=document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern');
         if(b){b.click();return true}return false})()"))

(defn pause-youtube!
  "Pause every YouTube tab, the main one and the reading-video one."
  []
  (doseq [y (filter (partial on-site? "youtube.com") (targets))] (yt-pause y)))

(defn nf-pause
  "Pause Netflix if it's playing — before YouTube takes the screen."
  []
  (when-let [t (first (filter (partial on-site? "netflix.com") (targets)))]
    (player-do t "if(p&&!p.isPaused())p.pause();return true")))

;; Every video link is /watch?v=<id> (Shorts are /shorts/ and left out). The
;; thumbnail is built from the id, so it doesn't depend on lazy-loaded <img>s;
;; the title is the longest text any link to that id carries.
(def yt-scrape-js
  "(()=>{const m=new Map();
   document.querySelectorAll('a[href*=\"/watch?v=\"]').forEach(a=>{
     const id=(a.getAttribute('href').match(/[?&]v=([\\w-]{11})/)||[])[1];if(!id)return;
     const box=a.closest('ytd-rich-item-renderer,ytd-video-renderer,yt-lockup-view-model,ytd-grid-video-renderer,ytd-compact-video-renderer,ytd-playlist-video-renderer');
     const txt=(a.getAttribute('title')||a.getAttribute('aria-label')||a.textContent||'').trim().replace(/\\s+/g,' ');
     const e=m.get(id)||{id:id,title:'',img:'https://i.ytimg.com/vi/'+id+'/mqdefault.jpg',channel:null};
     if(txt.length>e.title.length&&!/^[\\d:]+$/.test(txt))e.title=txt;
     if(!e.channel&&box){const c=box.querySelector('a[href^=\"/@\"],a[href^=\"/channel/\"]');
       if(c&&c.textContent.trim())e.channel=c.textContent.trim()}
     m.set(id,e)});
   const items=[...m.values()].filter(e=>e.title);
   return items.length?[{title:document.title.replace(/ - YouTube$/,''),items:items}]:[]})()")

(def yt-kind-js
  "(()=>{if(document.querySelector('a[href*=\"/watch?v=\"]'))return 'titles';
   if(document.querySelector('ytd-message-renderer,ytd-background-promo-renderer,a[href*=\"accounts.google.com/ServiceLogin\"]'))return 'empty';
   return null})()")

(defn yt-browse
  "Read the videos off a YouTube page in the YouTube tab. A few scrolls load
   more of the feed — it's endless, so this stops at about a hundred."
  [url]
  (reset! service "yt")
  (let [t (yt-target)]
    (nf-pause)
    (navigate t url)
    (Thread/sleep 1000)
    (case (wait-for t yt-kind-js 15000)
      "titles" (do (dotimes [_ 4]
                     (js t "window.scrollTo(0,document.documentElement.scrollHeight)")
                     (Thread/sleep 900))
                   (js t "window.scrollTo(0,0)")
                   {:kind "titles" :rows (js t yt-scrape-js)})
      "empty" {:kind "nothing" :note "YouTube shows nothing here — signed in on the TV?"}
      {:kind "nothing" :url (js t "location.href")})))

(defn play-in-tab!
  "Play YouTube video id in tab t, full-screen once it's going."
  [t id]
  (navigate t (str yt "/watch?v=" id))
  (when (wait-for t "(()=>{const p=document.getElementById('movie_player');
                       return location.pathname==='/watch'&&p&&p.getPlayerState&&p.getPlayerState()===1})()" 15000)
    ;; YouTube ignores keys for a moment after playback starts, so check and retry.
    (loop [n 0]
      (when (and (< n 3) (not (js t "!!document.fullscreenElement")))
        (Thread/sleep 1200)
        (press t "f")
        ;; f toggles, and full-screen takes a moment to land: wait for it
        ;; before deciding to press again, or the retry turns it back off.
        (wait-for t "!!document.fullscreenElement" 2500)
        (recur (inc n)))))
  true)

(defn yt-play
  "Play video id in the main YouTube tab."
  [id]
  (reset! service "yt")
  (nf-pause)
  (when-let [r (rv-target)] (yt-pause r))
  (play-in-tab! (yt-target) id))

(defn rv-play
  "Play video id from the page being read, in the reading-video tab (opened
   the first time), leaving the main YouTube tab and its memory alone."
  [id]
  (nf-pause)
  (when-let [m (first (filter main-yt? (targets)))] (yt-pause m))
  (reset! service "rv")
  (let [t (or (rv-target)
              (let [r (http/put (str "http://127.0.0.1:" cdp-port "/json/new?"
                                     (java.net.URLEncoder/encode (str yt "/") "UTF-8")) {:throw false})]
                (save-rv-id! (:id (json/parse-string (:body r) true)))
                (Thread/sleep 1500)
                (rv-target)))]
    (play-in-tab! t id)))

;; ---------------------------------------------------------------- Reading
;; A page opened for reading (bigscreen, or a link sent from the phone). The
;; remote only scripts the tab it opened itself, found by its id — never some
;; other page that happens to be in the window.

(def page-id
  "The reading tab's target id. Kept in state.edn, so a restarted server still
   knows the tab even after its address has changed (a post that forwards)."
  (atom nil))

(defn save-page-id! [id]
  (when (not= id @page-id) (reset! page-id id) (update-state! #(assoc % :page-id id))))

(def rl-id
  "The reading-link tab's target id. Kept in state.edn, like rv-id."
  (atom nil))

(defn page-target
  "The reading tab: by remembered id, else the tab showing the last page read,
   which then becomes the reading tab again. Never just any open page: the
   remote would run script on it every 15s, and on a Google sign-in page that
   once got the sign-in revoked (falsifier, 2026-10-11)."
  []
  (let [ts (targets)
        adopt (fn [t] (when t (save-page-id! (:id t)) t))]
    (or (when-let [id @page-id] (first (filter #(= id (:id %)) ts)))
        (when-let [u (:reading (load-state))]
          ;; never the link or video tab: a jump link's address starts with the page's
          (adopt (first (filter #(and (= "page" (:type %)) (not (#{@rv-id @rl-id} (:id %)))
                                      (str/starts-with? (str/replace (:url %) "://www." "://") (str/replace u "://www." "://"))) ts)))))))

(defn open-page
  "Open url as a new tab in front, and make it the reading tab."
  [url]
  (let [r (http/put (str "http://127.0.0.1:" cdp-port "/json/new?"
                         (java.net.URLEncoder/encode url "UTF-8")) {:throw false})
        t (json/parse-string (:body r) true)]
    (pause-youtube!)
    (nf-pause)
    (save-page-id! (:id t))
    (reset! service "page")
    (save-reading! url)
    (http/get (str "http://127.0.0.1:" cdp-port "/json/activate/" (:id t)) {:throw false})
    (some? (:id t))))

(defn page-scroll [t how]
  (js t (case how
          "down" "window.scrollBy({top:innerHeight*0.8,behavior:'smooth'});true"
          "up"   "window.scrollBy({top:-innerHeight*0.8,behavior:'smooth'});true"
          "top"  "window.scrollTo({top:0,behavior:'smooth'});true")))

;; Embeds (src or a lazy data-src) and plain links, in page order.
(def page-videos-js
  "(()=>{const out=[],seen=new Set();
   const add=(id,title)=>{if(id&&!seen.has(id)){seen.add(id);out.push({id:id,title:title||'Video '+(out.length+1),
     img:'https://i.ytimg.com/vi/'+id+'/mqdefault.jpg'})}};
   document.querySelectorAll('iframe,a[href]').forEach(e=>{
     const u=e.getAttribute('src')||e.getAttribute('data-src')||e.getAttribute('href')||'';
     const m=u.match(/youtube(?:-nocookie)?\\.com\\/(?:embed\\/|watch\\?v=)([\\w-]{11})|youtu\\.be\\/([\\w-]{11})/);
     if(m)add(m[1]||m[2],e.getAttribute('title')||(e.tagName==='A'?e.textContent.trim():''))});
   return out})()")

(def zooms
  "Text size per reading URL. CSS zoom is lost on reload, so it's kept here
   and put back."
  (atom {}))

(defn set-zoom
  "Zoom the reading tab: \"in\", \"out\" or \"reset\". CSS zoom reflows the text
   to the screen width, unlike pinch-style page scaling. Returns the new zoom."
  [t how]
  (let [url (:url t)
        z (get @zooms url 1.0)
        z (case how "in" (min 3.0 (+ z 0.1)) "out" (max 0.5 (- z 0.1)) "reset" 1.0 z)
        z (/ (Math/round (* z 10)) 10.0)]
    (swap! zooms assoc url z)
    (js t (str "document.documentElement.style.zoom='" z "';true"))
    z))

(defn zoom-of [user] (get-in (load-state) [:zoom user] 1.0))

(defn host-of [url] (some->> url (re-find #"//([^/]+)") second))

(defn tenth [z] (/ (Math/round (* z 10)) 10.0))

(defn set-zoom-2
  "Text size for reading, per person. On the page being read (link? false)
   A-/A+ set the reader's base size (state.edn :zoom); on a linked page they set
   an adjustment for that page's site (:zoom-adj {user {host adj}}), so a link
   shows at base + its site's adjustment. \"keep\" just applies it.
   Returns {:zoom applied, :adj the adjustment (links only)}."
  [t how user link?]
  (let [st   (load-state)
        host (host-of (:url t))
        base (get-in st [:zoom user] 1.0)
        adj  (get-in st [:zoom-adj user host] 0.0)
        step (case how "in" 0.1 "out" -0.1 0.0)
        base (if (or link? (= how "keep")) base
                 (if (= how "reset") 1.0 (tenth (min 3.0 (max 0.5 (+ base step))))))
        adj  (if (or (not link?) (= how "keep")) adj
                 (if (= how "reset") 0.0 (tenth (min (- 3.0 base) (max (- 0.5 base) (+ adj step))))))
        z    (tenth (min 3.0 (max 0.5 (+ base (if link? adj 0.0)))))]
    (when (not= how "keep")
      (update-state! #(if link?
                        (assoc-in % [:zoom-adj user host] adj)
                        (assoc-in % [:zoom user] base))))
    (js t (str "document.documentElement.style.zoom='" z "';true"))
    (cond-> {:zoom z} link? (assoc :adj adj))))

(defn page-back
  "Back to the reading tab, pausing whatever video was playing."
  [t]
  (pause-youtube!)
  (reset! service "page")
  (cdp t "Page.bringToFront")
  true)

;; Links from the page being read (2026-10-08), one level for now: a link opens
;; in its own reading-link tab (service "rl"), like a video in the reading-video
;; tab, so the page keeps its place and pressing Reading goes straight back.
;; Each further link reuses that tab.

(def page-links-js
  "(()=>{const out=[],seen=new Set(),here=location.href.split('#')[0];
   document.querySelectorAll('a[href]').forEach(a=>{
     const u=a.href,parts=u.split('#'),jump=parts[0]===here;
     if(!/^https?:/.test(u)||seen.has(u)||u===location.href||(jump&&!parts[1]))return;
     if(/youtube(?:-nocookie)?\\.com\\/(?:embed\\/|watch\\?v=)|youtu\\.be\\//.test(u))return;
     seen.add(u);
     out.push({url:u,jump:jump,text:(a.textContent.trim().replace(/\\s+/g,' ')||a.title||u).slice(0,120)})});
   return out})()")

(def page-links-js-2
  "page-links-js without the site's own furniture: links in a header, nav or
   footer (and quickblog's site-header, page-link and tag links)."
  (str/replace page-links-js "if(!/^https?:/"
               "if(a.closest('header,nav,footer,[role=navigation],.site-header,.site-nav,.page-link,.tag'))return;\n     if(!/^https?:/"))

(defn save-rl-id! [id] (reset! rl-id id) (update-state! #(assoc % :rl-id id)))

(defn rl-target []
  (when-let [id @rl-id] (first (filter #(= id (:id %)) (targets)))))

(defn rl-open
  "Open url in the reading-link tab. From a linked page it goes one level
   deeper in the same tab (Chrome drops any levels past the one showing, so the
   trail stays one line); from the page it starts a fresh trail in a new tab."
  [url]
  (let [cur (rl-target) deeper? (and cur (= "rl" @service))]
    (pause-youtube!)
    (nf-pause)
    (reset! service "rl")
    (cond
      ;; A jump within the page showing replaces its history entry rather than
      ;; adding one, so it doesn't add a breadcrumb level.
      (and deeper? (= (str/replace (:url cur) #"#.*" "") (str/replace url #"#.*" "")))
      (do (cdp cur "Page.bringToFront")
          (js cur (str "location.replace(" (json/generate-string url) ")"))
          true)

      deeper?
      (do (navigate cur url) true)

      :else
      (let [_  (when cur (http/get (str "http://127.0.0.1:" cdp-port "/json/close/" (:id cur)) {:throw false}))
            r  (http/put (str "http://127.0.0.1:" cdp-port "/json/new?"
                              (java.net.URLEncoder/encode url "UTF-8")) {:throw false})
            id (:id (json/parse-string (:body r) true))]
        (save-rl-id! id)
        (http/get (str "http://127.0.0.1:" cdp-port "/json/activate/" id) {:throw false})
        (some? id)))))

(defn rl-trail
  "The breadcrumbs: the reading-link tab's history as levels 1..n (Chrome's
   own entries, read over the protocol, no script), and which one is showing."
  []
  (when-let [t (rl-target)]
    (let [h  (cdp t "Page.getNavigationHistory")
          es (:entries h)]
      {:levels  (vec (for [e es :when (re-find #"^https?://" (:url e))]
                       {:id (:id e) :title (:title e)}))
       :current (:id (nth es (:currentIndex h) nil))})))

(defn rl-goto
  "Show breadcrumb level entry-id in the reading-link tab."
  [entry-id]
  (when-let [t (rl-target)]
    (pause-youtube!)
    (nf-pause)
    (reset! service "rl")
    (cdp t "Page.bringToFront")
    (when (not= entry-id (:current (rl-trail)))
      (cdp t "Page.navigateToHistoryEntry" {:entryId entry-id}))
    true))

(defn reading-target
  "The tab the reading panel's buttons act on: the link while one is showing, else the page."
  []
  (or (when (= "rl" @service) (rl-target)) (page-target)))

;; ---------------------------------------------------------------- Status

(defn status
  "Polled by the phone every few seconds, so it only looks: it never opens a
   tab, and says so when the service's tab is off-site (e.g. signing in)."
  []
  (let [ts (targets)
        tab (fn [site] (first (filter (partial on-site? site) ts)))
        svc @service
        pg (page-target)
        add-page #(cond-> % pg (assoc :page {:title (:title pg) :url (:url pg)}))]
    (add-page (cond
      (nil? ts) {:chrome false :others (count (other-chromes))}
      (= "page" svc) {:chrome true :service "page"}
      (= "rv" svc) (if-let [t (first (filter #(= @rv-id (:id %)) ts))]
                     (assoc (yt-do t yt-status-js) :chrome true :service "rv")
                     {:chrome true :service "rv" :away true})
      (= "yt" svc) (if-let [t (first (filter main-yt? ts))]
                     (assoc (yt-do t yt-status-js) :chrome true :service "yt")
                     {:chrome true :service "yt" :away true})
      :else (if-let [t (tab "netflix.com")]
              (let [m  (try (js t nf-meta-js) (catch Exception _ nil))
                    ;; "Who's watching?" showing by itself (after Start, say):
                    ;; its names go to the phone without a press of Home
                    ps (when-not (str/includes? (:url t) "/watch")
                         (try (seq (js t profiles-js)) (catch Exception _ nil)))]
                (cond-> (assoc (player-do t status-js) :chrome true :service "nf" :picked @picked)
                  m  (assoc :title (:title m) :episode (:episode m))
                  ps (assoc :profiles (vec ps))))
              {:chrome true :service "nf" :away true})))))

;; status, plus a link from Reading — read from Chrome's target list only.
(defn recent
  "The first 5 of user's history for the service svc shows under."
  [user svc]
  (when-let [k (and user ({"nf" :nf "yt" :yt "page" :page "rv" :page "rl" :page} svc))]
    (vec (for [w (take 5 (get-in (load-state) [:history user k]))]
           {:title (or (:title w) (:url w) (:id w))
            :sub   (case k
                     :nf   (:episode w)
                     :yt   (:channel w)
                     :page (some->> (:url w) (re-find #"//([^/]+)") second))}))))

(defn status-2 [user]
  (let [svc   @service
        trail (when (#{"page" "rl"} svc) (try (rl-trail) (catch Exception _ nil)))]
    (cond-> (if (and (= "rl" svc) (chrome-up?))
              (let [l (rl-target) pg (page-target)]
                (cond-> {:chrome true :service "rl"}
                  l  (assoc :link {:title (:title l) :url (:url l)})
                  pg (assoc :page {:title (:title pg) :url (:url pg)})))
              (status))
      (seq (:levels trail)) (assoc :trail trail)
      user                  (assoc :recent (recent user svc))
      (#{"page" "rl"} svc)  (merge (let [b (zoom-of user) l (and (= "rl" svc) (rl-target))]
                                     (if l
                                       (let [a (get-in (load-state) [:zoom-adj user (host-of (:url l))] 0.0)]
                                         {:zoom (tenth (min 3.0 (max 0.5 (+ b a)))) :adj a})
                                       {:zoom b}))))))

;; ---------------------------------------------------------------- Watching
;; One current service — "nf", "yt" or "page" — and, for the two players, what
;; was last watched, so switching back finds it loaded and paused.

(defn pause-all []
  (pause-youtube!)
  (nf-pause))

(defn save-watched! [svc m]
  (update-state! #(assoc-in % [:watched svc] m)))

(def last-saved "What record-watching! last wrote, per service." (atom {}))

(defn record-watching!
  "Save what each player tab is on, so a switch back can load it again.
   Netflix: the /watch/<episode> address (Netflix keeps the position itself).
   YouTube: the video and the second reached — written when the video changes
   or has moved on 15s. Runs every 15s from -main, phone open or not."
  []
  (let [ts (targets)]
    (when-let [n (first (filter (partial on-site? "netflix.com") ts))]
      (when-let [u (re-find #"https://www\.netflix\.com/watch/\d+" (:url n))]
        (let [title (or (try (js n nf-title-js) (catch Exception _ nil)) @picked)
              prev  (:nf @last-saved)]
          (when (or (not= u (:url prev)) (and title (not= title (:title prev))))
            (let [m {:url u :title title}]
              (save-watched! :nf m)
              (swap! last-saved assoc :nf m))))))
    (when-let [y (first (filter main-yt? ts))]
      (when (str/includes? (:url y) "/watch")
        (let [s   (yt-do y yt-status-js)
              id  (second (re-find #"[?&]v=([\w-]{11})" (or (:url s) "")))
              sec (some-> (:time s) (/ 1000) long)
              prev (:yt @last-saved)]
          (when (and id sec (or (not= id (:id prev)) (>= (abs (- sec (:time-s prev 0))) 15)))
            (let [m {:id id :title (:title s) :channel (:picked s) :time-s sec}]
              (save-watched! :yt m)
              (swap! last-saved assoc :yt m))))))))

;; Per person (2026-10-07): each phone says who holds it (`u`, kept in the
;; phone's localStorage), and what each player tab is on is saved for whoever
;; started it — the service's `owner` — under [:watched-by user svc]. With no
;; user (an old phone page) the shared :watched above is used as before.

(def owner
  "Who started what's in each player tab: {\"nf\" \"ann\", \"yt\" \"bob\"}."
  (atom {}))

(def jump-js
  "Put a jump target (the address's #part) a little way down the screen: the
   browser jumps before the text size is applied, which then moves it away, and
   it lines the target up flush with the top edge, where the TV crops it."
  "(()=>{document.documentElement.style.scrollPaddingTop='12vh';
   const h=decodeURIComponent(location.hash.slice(1));if(!h)return false;
   const e=document.getElementById(h)||document.getElementsByName(h)[0];
   if(!e)return false;
   const b=(e.textContent.trim()===''&&e.parentElement)?e.parentElement:e;
   b.scrollIntoView({block:'start'});return true})()")

(defn zoom-when-loaded!
  "Once tab t has loaded, give it the reader's text size (the page's owner),
   then go to its jump target, if it has one."
  [t]
  (Thread/sleep 800)
  (when (wait-for t "document.readyState==='complete'" 15000)
    (set-zoom-2 t "keep" (get @owner "page") (= (:id t) @rl-id))
    (js t jump-js)))

(defn set-owner! [svc user]
  (when (and user (#{"nf" "yt" "page"} svc) (not= user (get @owner svc)))
    (swap! owner assoc svc user)
    (update-state! #(assoc % :owner @owner))))

(defn watched-for
  "What user last watched on svc (:nf or :yt); the shared record until they have one."
  [user svc]
  (let [st (load-state)]
    (or (get-in st [:watched-by user svc]) (get-in st [:watched svc]))))

(defn watched-for-2
  "What user last watched on svc (:nf or :yt), or nil: never someone else's."
  [user svc]
  (get-in (load-state) [:watched-by user svc]))

(defn save-watched-by! [user svc m]
  (update-state! #(if user
                    (assoc-in % [:watched-by user svc] m)
                    (assoc-in % [:watched svc] m))))

(def last-saved-2 "What record-watching-2! last wrote, per [user svc]." (atom {}))

;; History (2026-10-09): everything each person has watched or read, per
;; service, newest first, 100 kept — [:history user :nf|:yt|:page]. One entry
;; per :key: a Netflix show (so the next episode replaces its line), a YouTube
;; video, a page URL. The phone shows the first 5 of the current service.

(defn add-history! [user svc m]
  (when user
    (update-state!
     (fn [st] (assoc-in st [:history user svc]
                        (->> (get-in st [:history user svc])
                             (remove #(= (:key m) (:key %)))
                             (cons m)
                             (take 100)
                             vec))))))

;; Opened (2026-10-10): what each person opened from their phone, per service
;; — {"yt" {:user "ann" :key "<video id>"} ...}, kept in state.edn :opened.
;; Recent and last-watched are written only for what was opened: the 15s check
;; updates its place but never adds something played on the TV directly or by
;; YouTube's autoplay. A nil :key is filled in by the first check (a page's
;; real address, after any redirect). Netflix is keyed by the show's name,
;; the first part of the title Netflix shows while playing, so the next
;; episodes of an opened show still follow but another show played by hand
;; doesn't (2026-10-10).

(def opened (atom {}))

(defn set-opened! [svc user key]
  (when user
    (swap! opened assoc svc {:user user :key key})
    (update-state! #(assoc % :opened @opened))))

(defn claim
  "The person to save svc's current item (key) for, or nil if no phone opened it."
  [svc key]
  (when-let [{:keys [user] k :key} (get @opened svc)]
    (cond (nil? k)     (do (set-opened! svc user key) user)
          (= k key)    user)))

(def page-place-js
  "(()=>{const h=document.documentElement.scrollHeight-innerHeight;
   return {url:location.href,title:document.title,scroll:h>0?scrollY/h:0}})()")

(def nf-meta-missing
  "Whether the log has been told Netflix's player data is missing (once a run)."
  (atom false))

(defn record-watching-2!
  "record-watching!, saved for each tab's owner, and with Netflix's second too
   (so two people in the same show each get their own place back)."
  []
  (let [ts (targets)]
    (when-let [n (first (filter (partial on-site? "netflix.com") ts))]
      (when-let [u (re-find #"https://www\.netflix\.com/watch/\d+" (:url n))]
        (let [s     (player-do n status-js)
              meta  (try (js n nf-meta-js) (catch Exception _ nil))
              _     (if meta
                      (reset! nf-meta-missing false)
                      (when (and (:playing s) (compare-and-set! nf-meta-missing false true))
                        (println "Netflix: no player data (nf-meta-js) while playing - has Netflix changed? Episodes Netflix moves on to won't be recorded until it's fixed.")))
              s     (cond-> s meta (assoc :title (:title meta) :episode (or (:episode meta) (:episode s))))
              show  (or (:show meta) (some-> (:title s) (str/split #" · ") first))
              o     (get @opened "nf")
              prev  (get @last-saved-2 [(:user o) :nf])
              ;; the show decides whose it is; with no title showing, the same
              ;; episode as last saved is still the opener's
              user  (cond show (claim "nf" show)
                          (and o (= u (:url prev))) (:user o))
              title (or (:title s) (when (= u (:url prev)) (:title prev)))
              sec   (some-> (:time s) (/ 1000) long)]
          (when (and user
                     (or (not= u (:url prev)) (and title (not= title (:title prev)))
                         (and sec (>= (abs (- sec (:time-s prev 0))) 15))))
            (let [m {:url u :title title :time-s sec}]
              (save-watched-by! user :nf m)
              (add-history! user :nf (assoc m :key (or show (some-> title (str/split #" · ") first) u)
                                              :episode (:episode s)))
              (swap! last-saved-2 assoc [user :nf] m))))))
    (when-let [y (first (filter main-yt? ts))]
      (when (str/includes? (:url y) "/watch")
        (let [s    (yt-do y yt-status-js)
              id   (second (re-find #"[?&]v=([\w-]{11})" (or (:url s) "")))
              user (claim "yt" id)
              sec  (some-> (:time s) (/ 1000) long)
              prev (get @last-saved-2 [user :yt])]
          ;; no title: an unavailable or not-yet-loaded video, never saved over a good entry
          (when (and user id sec (not (str/blank? (:title s))) (or (not= id (:id prev)) (>= (abs (- sec (:time-s prev 0))) 15)))
            (let [m {:id id :title (:title s) :channel (:picked s) :time-s sec}]
              (save-watched-by! user :yt m)
              (add-history! user :yt (assoc m :key id))
              (swap! last-saved-2 assoc [user :yt] m))))))
    (when-let [p (page-target)]
      (let [s    (try (js p page-place-js) (catch Exception _ nil))
            user (when s (claim "page" (:url s)))
            prev (get @last-saved-2 [user :page])]
        (when (and user s (re-find #"^https?://" (:url s))
                   (or (not= (:url s) (:url prev)) (not= (:title s) (:title prev))
                       (> (abs (- (:scroll s) (:scroll prev 0))) 0.005)
                       (not= (zoom-of user) (:zoom prev))))
          (when (not= (:url s) (:reading (load-state))) (save-reading! (:url s)))
          (let [m {:key (:url s) :url (:url s) :title (:title s) :scroll (:scroll s)
                   :zoom (zoom-of user)}]
            (add-history! user :page m)
            (swap! last-saved-2 assoc [user :page] m)))))))

(def nf-paused-js (str "(()=>{const p=" player-js ";return p?p.isPaused():null})()"))
(def nf-pause-js  (str "(()=>{const p=" player-js ";if(p)p.pause();return true})()"))
;; 2 paused, 5 cued, 0 ended: all count as not playing
(def yt-paused-js "(()=>{const p=document.getElementById('movie_player');
  return (p&&p.getPlayerState&&location.pathname==='/watch')?[0,2,5].includes(p.getPlayerState()):null})()")
(def yt-pause-js "(()=>{const p=document.getElementById('movie_player');if(p&&p.pauseVideo)p.pauseVideo();return true})()")

(defn hold-paused!
  "Wait for the player in tab t and keep it paused. Autoplay can start AFTER a
   first pause, so it has to read paused three checks running (up to ~20s)."
  [t pause-js paused-js]
  (loop [n 0 still 0]
    (when (and (< n 40) (< still 3))
      (Thread/sleep 500)
      (if (true? (try (js t paused-js) (catch Exception _ nil)))
        (recur (inc n) (inc still))
        (do (try (js t pause-js) (catch Exception _ nil))
            (recur (inc n) 0))))))

(defn switch-service!
  "Make svc (\"nf\", \"yt\" or \"page\") the current one: pause everything,
   bring its tab forward, and — for a player tab that is no longer on a watch
   page — load what was last watched there and hold it paused."
  [svc]
  (pause-all)
  (case svc
    "page" (if-let [p (page-target)]
             (do (reset! service "page") (cdp p "Page.bringToFront") {:ok true})
             {:ok false :error "Nothing open for reading — open a link, or ask for a page"})
    "nf"   (let [t (tv-target) w (get-in (load-state) [:watched :nf])]
             (reset! service "nf")
             (cdp t "Page.bringToFront")
             (if (and w (not (str/includes? (:url t) "/watch/")))
               (do (when (:title w) (reset! picked (:title w)))
                   (cdp t "Page.navigate" {:url (:url w)})
                   (hold-paused! t nf-pause-js nf-paused-js)
                   {:ok true :loaded (or (:title w) "the last episode")})
               {:ok true}))
    "yt"   (let [t (yt-target) w (get-in (load-state) [:watched :yt])]
             (reset! service "yt")
             (cdp t "Page.bringToFront")
             (if (and w (not (str/includes? (:url t) "/watch")))
               (do (cdp t "Page.navigate" {:url (str yt "/watch?v=" (:id w) "&t=" (:time-s w 0) "s")})
                   (hold-paused! t yt-pause-js yt-paused-js)
                   {:ok true :loaded (or (:title w) "the last video")})
               {:ok true}))
    {:ok false :error (str "unknown service " svc)}))

(defn load-nf!
  "Load Netflix watch record w in tab t, held paused at its second."
  [t w]
  (reset! picked (:title w))
  (cdp t "Page.navigate" {:url (:url w)})
  (hold-paused! t nf-pause-js nf-paused-js)
  (when-let [ms (some-> (:time-s w) (* 1000))]
    (player-do t (str "if(!p)return false;
      if(Math.abs(p.getCurrentTime()-" ms ")>15000)p.seek(" ms ");return true"))))

(defn load-yt!
  "Load YouTube watch record w in tab t, held paused at its second."
  [t w]
  (cdp t "Page.navigate" {:url (str yt "/watch?v=" (:id w) "&t=" (:time-s w 0) "s")})
  (hold-paused! t yt-pause-js yt-paused-js))

(defn load-page!
  "Show url in the reading tab (opened if there isn't one) at scroll, a
   fraction of the way down, with its text size put back."
  [url scroll]
  (if-let [p (page-target)]
    (do (reset! service "page") (save-reading! url) (navigate p url))
    (open-page url))
  (Thread/sleep 1000)
  (when-let [p (page-target)]
    (when (wait-for p "document.readyState==='complete'" 15000)
      (let [p (or (page-target) p)]
        (set-zoom-2 p "keep" (get @owner "page") false)
        (js p (str "(()=>{const h=document.documentElement.scrollHeight-innerHeight;
                     scrollTo(0," (or scroll 0) "*h);return true})()")))))
  true)

(defn switch-service-2!
  "switch-service! for user: a player tab is reloaded with what USER last
   watched unless it is already on a watch page that user started. The tab's
   previous owner's place is saved first, and user becomes the owner of what
   gets loaded. Netflix is also sent to the saved second. Switching to Reading
   leaves an open page with whoever opened it; with none open, user's latest
   Recent page is reopened as theirs."
  [svc user]
  (if (or (nil? user) (not (#{"nf" "yt"} svc)))
    (do (when (and user (= "page" svc))
          (try (record-watching-2!) (catch Exception _ nil)))
        (let [w (and user (= "page" svc) (nil? (page-target))
                     (first (get-in (load-state) [:history user :page])))]
          (if w
            ;; nothing open for reading: reopen the latest Recent page
            (do (set-owner! "page" user)
                (set-opened! "page" user nil)
                (load-page! (:url w) (:scroll w)) {:ok true :loaded (or (:title w) (:url w))})
            (switch-service! svc))))
    (do
      (try (record-watching-2!) (catch Exception _ nil))
      (pause-all)
      (case svc
        "nf" (let [t (tv-target) w (watched-for-2 user :nf)]
               (reset! service "nf")
               (cdp t "Page.bringToFront")
               (if (and w (not (and (str/includes? (:url t) "/watch/") (= user (get @owner "nf")))))
                 (do (set-owner! "nf" user)
                     (set-opened! "nf" user nil)
                     (load-nf! t w)
                     {:ok true :loaded (or (:title w) "the last episode")})
                 {:ok true}))
        "yt" (let [t (yt-target) w (watched-for-2 user :yt)]
               (reset! service "yt")
               (cdp t "Page.bringToFront")
               (if (and w (not (and (str/includes? (:url t) "/watch") (= user (get @owner "yt")))))
                 (do (set-owner! "yt" user)
                     (set-opened! "yt" user (:id w))
                     (load-yt! t w)
                     {:ok true :loaded (or (:title w) "the last video")})
                 {:ok true}))))))

(defn resume!
  "Load entry i of user's recent svc (\"nf\", \"yt\" or \"page\"): a show or
   video paused at its place, a page at its scroll position."
  [svc user i]
  (if-let [w (and user (nth (get-in (load-state) [:history user (keyword svc)]) i nil))]
    (do (try (record-watching-2!) (catch Exception _ nil))
        (pause-all)
        (set-owner! svc user)
        (set-opened! svc user (when (= "yt" svc) (:id w)))
        (case svc
          "nf"   (let [t (tv-target)]
                   (reset! service "nf") (cdp t "Page.bringToFront") (load-nf! t w))
          "yt"   (let [t (yt-target)]
                   (reset! service "yt") (cdp t "Page.bringToFront") (load-yt! t w))
          "page" (do (when (:zoom w)
                       ;; the page's own size becomes the reader's base again
                       (update-state! #(assoc-in % [:zoom user] (:zoom w))))
                     (load-page! (:url w) (:scroll w))))
        {:ok true :loaded (or (:title w) "it")})
    {:ok false :error "That's no longer in the list"}))

;; ---------------------------------------------------------------- Tabs
;; The tabs on the TV, for switching. Listing them reads Chrome's target list
;; only — no script runs on any of them.

(defn user-tabs []
  (for [t (targets)
        :when (and (= "page" (:type t)) (re-find #"^https?://" (:url t)))]
    {:id (:id t) :title (:title t) :url (:url t)
     :kind (cond (on-site? "netflix.com" t) "nf" (= (:id t) @rv-id) "rv"
                 (on-site? "youtube.com" t) "yt" :else "page")
     :reading (= (:id t) @page-id)}))

(defn switch-tab
  "Bring tab id to the front. Netflix/YouTube become the current service; any
   other page becomes the reading tab. Whatever was playing pauses."
  [id]
  (when-let [t (first (filter #(= id (:id %)) (targets)))]
    (cond (= (:id t) @rv-id)
          (do (pause-all) (reset! service "rv")
              (= 200 (:status (http/get (str "http://127.0.0.1:" cdp-port "/json/activate/" id) {:throw false}))))
          (on-site? "netflix.com" t) (:ok (switch-service! "nf"))
          (on-site? "youtube.com" t) (:ok (switch-service! "yt"))
          :else (do (pause-all)
                    (save-page-id! id) (reset! service "page") (save-reading! (:url t))
                    (= 200 (:status (http/get (str "http://127.0.0.1:" cdp-port "/json/activate/" id) {:throw false})))))))

(defn switch-tab-2
  "switch-tab, with a Netflix or YouTube tab switched to for user."
  [id user]
  (when-let [t (first (filter #(= id (:id %)) (targets)))]
    (cond (= (:id t) @rv-id) (switch-tab id)
          (= (:id t) @rl-id)
          (do (pause-all) (reset! service "rl")
              (= 200 (:status (http/get (str "http://127.0.0.1:" cdp-port "/json/activate/" id) {:throw false}))))
          (on-site? "netflix.com" t) (:ok (switch-service-2! "nf" user))
          (on-site? "youtube.com" t) (:ok (switch-service-2! "yt" user))
          :else (switch-tab id))))

(defn open-standard-tabs!
  "After a Chrome start: Netflix (the start page) in front, with YouTube and the
   last page read behind it."
  []
  (yt-target)
  (when-let [u (:reading (load-state))]
    (let [r (http/put (str "http://127.0.0.1:" cdp-port "/json/new?"
                           (java.net.URLEncoder/encode u "UTF-8")) {:throw false})]
      (save-page-id! (:id (json/parse-string (:body r) true)))
      ;; the reader's text size; in the background so Start isn't held up
      (future (some-> (page-target) zoom-when-loaded!))))
  (when-let [n (first (filter (partial on-site? "netflix.com") (targets)))]
    (http/get (str "http://127.0.0.1:" cdp-port "/json/activate/" (:id n)) {:throw false})))

(defn close-tab [id]
  (when (= id @page-id) (save-page-id! nil))
  (when (= id @rv-id) (save-rv-id! nil))
  (= 200 (:status (http/get (str "http://127.0.0.1:" cdp-port "/json/close/" id) {:throw false}))))

;; ---------------------------------------------------------------- HTTP

(def page (delay (slurp (str base-dir "/tvremote.html"))))

(defn reply [m] {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string m)})

(defn query [req]
  (into {} (for [kv (str/split (or (:query-string req) "") #"&")
                 :let [[k v] (str/split kv #"=" 2)] :when (seq k)]
             [(keyword k) (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(defn wake-screen!
  "Un-blank the TV. Screen lock is off on this machine (2026-10-04), so this
   is all it takes; a blanked screen also stops Chrome drawing, and Netflix
   only loads rows while it draws."
  []
  (try (p/shell {:out :discard :err :discard
                 :extra-env {"DBUS_SESSION_BUS_ADDRESS" (str "unix:path=" runtime-dir "/bus")}}
                "gdbus" "call" "--session" "--dest" "org.gnome.ScreenSaver"
                "--object-path" "/org/gnome/ScreenSaver"
                "--method" "org.gnome.ScreenSaver.SetActive" "false")
       (catch Exception _ nil)))

(defn yt-act
  "The transport buttons on YouTube tab t."
  [t action q]
  (case action
    "toggle" {:ok (yt-toggle t)}
    "seek"   {:ok (yt-seek t (parse-long (:ms q)))}
    "volume" {:ok (yt-volume t (parse-double (:d q)))}
    "skip"   (if (yt-skip t) {:ok true} {:ok false :error "No ad to skip"})
    "full"   (do (press t "f") {:ok true})))

(defn nf-mark
  "A Netflix browse or play makes Netflix the current service again, and
   pauses YouTube so the two don't play over each other."
  [action]
  (when (#{"play" "browse" "shows" "movies" "genre" "search" "profile" "home"} action)
    (when (#{"yt" "rv"} @service) (pause-youtube!))
    (reset! service "nf")))

(defn user-of
  "Who the phone says holds it (?u=, from its localStorage), or nil."
  [q]
  (re-matches #"[a-z]{1,20}" (or (:u q) "")))

(defn share-html
  "The page the TV shows for Share: a QR code of the remote's address (as the
   phone sees it), for another phone's camera."
  [addr]
  (str "<!doctype html><html><head><meta charset=utf-8><title>Share the TV remote</title>
<script src='https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js'></script>
<style>body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;
background:#141414;color:#eee;font:28px system-ui,sans-serif;text-align:center}
#qr{background:#fff;padding:24px;border-radius:16px;margin:28px}small{color:#999;font-size:22px}</style></head>
<body><div style='font-size:44px'>📱 Scan with your phone's camera</div>
<div id=qr></div><div>http://" addr "/</div>
<small>Then add it to your home screen. This goes away in 2 minutes.</small>
<script>new QRCode(document.getElementById('qr'),{text:'http://" addr "/',width:420,height:420})</script>
</body></html>"))

(def share-id (atom nil))

(defn share-off!
  "Take the QR code down (only if it is still tab id, when given) and bring
   back what was showing."
  ([] (share-off! @share-id))
  ([id]
   (when (and id (= id @share-id))
     (close-tab id)
     (reset! share-id nil)
     (some-> (case @service "nf" (tv-target) "yt" (yt-target) "page" (page-target)
                   "rl" (rl-target) nil)
             (cdp "Page.bringToFront")))
   {:ok true}))

(defn share!
  "Put the QR code for addr (host:port, from the phone) in front on the TV for
   two minutes, then close it and bring back what was showing."
  [addr]
  (if-not (re-matches #"[\w.\-]+(:\d+)?" (or addr ""))
    {:ok false :error "Bad address"}
    (let [r  (http/put (str "http://127.0.0.1:" cdp-port "/json/new?"
                            (java.net.URLEncoder/encode (str "http://127.0.0.1:" port "/share?a=" addr) "UTF-8"))
                       {:throw false})
          id (:id (json/parse-string (:body r) true))]
      (some-> @share-id close-tab)
      (reset! share-id id)
      (future (Thread/sleep 120000) (share-off! id))
      {:ok (boolean id)})))

(defn act [action q]
  (wake-screen!)
  (if (and (not (chrome-up?)) (not (#{"start" "close-all"} action)))
    {:ok false :error "Chrome isn't running — press Start"}
    ;; looked up only when used: finding it opens a Netflix tab if there's none
    (let [t (delay (tv-target))]
      (nf-mark action)
      (case action
        ("toggle" "seek" "volume" "skip" "full")
        (cond
          (#{"page" "rl"} @service) {:ok false :error "Reading is current — switch to Netflix or YouTube for a video"}
          (= "yt" @service) (yt-act (yt-target) action q)
          (= "rv" @service) (if-let [r (rv-target)]
                              (yt-act r action q)
                              {:ok false :error "The reading video's tab is gone — play it again from the page"})
          :else
          (case action
            "toggle"  {:ok (toggle @t)}
            "seek"    {:ok (seek @t (parse-long (:ms q)))}
            "volume"  {:ok (volume @t (parse-double (:d q)))}
            "skip"    (if (skip @t) {:ok true} {:ok false :error "Nothing to skip right now"})
            "full"    (do (press @t "f") {:ok true})))
        "close-all"  (if (chrome-up?)
                       {:ok false :error "Only while the remote's Chrome is closed"}
                       (close-other-chromes!))
        "switch"     (let [r (switch-service-2! (:to q) (user-of q))]
                       ;; nothing of theirs to resume: the phone reads the titles instead
                       (cond-> r (and (:ok r) (not (:loaded r)) (#{"nf" "yt"} (:to q))
                                      (nil? (watched-for-2 (user-of q) (keyword (:to q)))))
                         (assoc :fresh true)))
        "page-video" {:ok (rv-play (re-find #"[\w-]{11}" (:id q)))}
        "open"       (do (set-owner! "page" (user-of q))
                         ;; bigscreen sends no user: the page is the current reader's
                         (set-opened! "page" (or (user-of q) (get @owner "page")) nil)
                         (let [ok (open-page (:url q))]
                           (when ok (some-> (page-target) zoom-when-loaded!))
                           {:ok ok}))
        "resume"     (resume! (:svc q) (user-of q) (parse-long (:i q)))
        "tabs"       {:ok true :tabs (user-tabs)}
        "share"      (share! (:host q))
        "share-off"  (share-off!)
        "tab"        {:ok (boolean (switch-tab-2 (:id q) (user-of q)))}
        "tab-close"  {:ok (close-tab (:id q))}
        "page-scroll" (if-let [p (reading-target)] {:ok (page-scroll p (:how q))} {:ok false :error "No page open for reading"})
        "page-videos" (if-let [p (page-target)] {:ok true :videos (js p page-videos-js)} {:ok false :error "No page open for reading"})
        ;; ignoreCache so a just-republished page really is fetched again;
        ;; Chrome keeps the scroll position across a reload.
        "page-reload" (if-let [p (reading-target)]
                          (do (cdp p "Page.reload" {:ignoreCache true})
                              (Thread/sleep 500)
                              (when (wait-for p "document.readyState==='complete'" 15000)
                                (set-zoom-2 p "keep" (get @owner "page") (= (:id p) @rl-id)))
                              {:ok true})
                          {:ok false :error "No page open for reading"})
        "page-zoom"  (if-let [p (reading-target)] (assoc (set-zoom-2 p (:how q) (or (user-of q) (get @owner "page")) (= (:id p) @rl-id)) :ok true) {:ok false :error "No page open for reading"})
        "page-links" (if-let [p (reading-target)] {:ok true :links (js p page-links-js-2)} {:ok false :error "No page open for reading"})
        "page-link"  (do (set-owner! "page" (user-of q))
                         (let [ok (rl-open (:url q))]
                           (when ok (some-> (rl-target) zoom-when-loaded!))
                           {:ok ok}))
        "crumb"      (let [ok (boolean (rl-goto (parse-long (:id q))))]
                       (when ok (some-> (rl-target) zoom-when-loaded!))
                       {:ok ok})
        "page-back"  (if-let [p (page-target)] {:ok (page-back p)} {:ok false :error "No page open for reading"})
        "yt-home"    (assoc (yt-browse (str yt "/")) :ok true)
        "yt-subs"    (assoc (yt-browse (str yt "/feed/subscriptions")) :ok true)
        "yt-history" (assoc (yt-browse (str yt "/feed/history")) :ok true)
        "yt-search"  (assoc (yt-browse (str yt "/results?search_query=" (java.net.URLEncoder/encode (:q q) "UTF-8"))) :ok true)
        "yt-play"    (do (try (record-watching-2!) (catch Exception _ nil))
                         (set-owner! "yt" (user-of q))
                         (set-opened! "yt" (user-of q) (re-find #"[\w-]{11}" (:id q)))
                         {:ok (yt-play (re-find #"[\w-]{11}" (:id q)))})
        ;; Never alongside another Chrome: one window on the TV, and the other
        ;; one closed means it has stopped playing (Chris, 2026-10-07).
        "start"   (cond
                    (chrome-up?) {:ok true :note "already running"}
                    ;; Pressing Start (or bigscreen) means "take over the TV": another
                    ;; Chrome there is closed first, not left blocking (2026-10-10).
                    (and (seq (other-chromes)) (not (:ok (close-other-chromes!))))
                    {:ok false :error "Couldn't close the other Chrome"}
                    :else
                    (let [ok (launch-chrome!)]
                      ;; Netflix is in front after a start, so it's current
                      (when ok (Thread/sleep 1500) (open-standard-tabs!) (reset! service "nf"))
                      {:ok ok}))
        "home"    (do (navigate @t (str nf "/browse")) {:ok true})
        "play"    (do (try (record-watching-2!) (catch Exception _ nil))
                    (set-owner! "nf" (user-of q))
                    (set-opened! "nf" (user-of q) nil)
                    (reset! picked (:t q))
                    (navigate @t (str nf "/watch/" (re-find #"\d+" (:id q)))) {:ok true})
        "browse"  (assoc (browse (str nf "/browse")) :ok true)
        "shows"   (assoc (browse (str nf "/browse/genre/83")) :ok true)
        "movies"  (assoc (browse (str nf "/browse/genre/34399")) :ok true)
        "genre"   (assoc (browse (str nf "/browse/genre/" (re-find #"\d+" (:id q)))) :ok true)
        "search"  (assoc (browse (str nf "/search?q=" (java.net.URLEncoder/encode (:q q) "UTF-8"))) :ok true)
        "profile" (assoc (pick-profile (parse-long (:i q))) :ok true)
        {:ok false :error (str "unknown action " action)}))))

(defn ensure-fullscreen!
  "Put the TV's Chrome window back to full screen if it has dropped out:
   leaving a video's full screen can leave the window normal, and there's no
   keyboard at the TV to press F11 (Chris, 2026-10-11)."
  []
  (when-let [t (first (filter #(= "page" (:type %)) (targets)))]
    (let [w (cdp t "Browser.getWindowForTarget")]
      (when-not (= "fullscreen" (get-in w [:bounds :windowState]))
        (cdp t "Browser.setWindowBounds" {:windowId (:windowId w) :bounds {:windowState "fullscreen"}})))))

(defn handler [req]
  (let [uri (:uri req)]
    (try
      (cond
        (= uri "/") {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body @page}
        (= uri "/status") (reply (cond-> (status-2 (user-of (query req))) @share-id (assoc :sharing true)))
        (str/starts-with? uri "/act/")
        (let [action (subs uri 5)
              r      (act action (query req))]
          ;; after every press, so the window never stays out of full screen
          (when-not (= "close-all" action)
            (future (try (ensure-fullscreen!) (catch Exception _ nil))))
          (reply r))
        :else {:status 404 :body "not found"})
      (catch Exception e
        (reply {:ok false :error (or (ex-message e) (str e))})))))

(defn adopt-front-tab!
  "A restarted server has forgotten what the TV shows (service starts as
   \"nf\"), so take it from the front tab: Chrome lists the most recently
   active tab first."
  []
  (when-let [t (first (filter #(and (= "page" (:type %)) (re-find #"^https?://" (:url %))) (targets)))]
    (cond (= (:id t) @rv-id) (reset! service "rv")
          (= (:id t) @rl-id) (reset! service "rl")
          (on-site? "netflix.com" t) (reset! service "nf")
          (on-site? "youtube.com" t) (reset! service "yt")
          (= (:id t) @page-id) (reset! service "page"))))

(defn handler-2
  "handler, logging every button press to the journal: who, what, how long,
   and the error if it failed. /status (polled every 3s) is not logged."
  [req]
  (if-not (str/starts-with? (:uri req) "/act/")
    (handler req)
    (let [t0   (System/currentTimeMillis)
          q    (query req)
          res  (handler req)
          ms   (- (System/currentTimeMillis) t0)
          out  (try (json/parse-string (:body res) true) (catch Exception _ nil))
          args (for [[k v] (dissoc q :u)] (str (name k) "=" (subs v 0 (min 80 (count v)))))]
      (println (str/join " " (concat [(or (:u q) "-") (subs (:uri req) 5)] args
                                     [(str ms "ms") (if (:ok out) "ok" (str "FAILED: " (:error out)))])))
      (flush)
      res)))

(defn handler-3
  "handler-2, plus /config: the names the phone offers (the :people setting)."
  [req]
  (if (= "/config" (:uri req))
    (reply {:people (:people config)})
    (handler-2 req)))

(def home-screen-files
  "What a phone fetches to put the remote on its home screen: name and icons."
  {"/manifest.json"        ["manifest.json" "application/manifest+json"]
   "/icon-192.png"         ["icons/icon-192.png" "image/png"]
   "/icon-512.png"         ["icons/icon-512.png" "image/png"]
   "/apple-touch-icon.png" ["icons/icon-180.png" "image/png"]})

(defn handler-4
  "handler-3, plus the home-screen files."
  [req]
  (if-let [[f ct] (home-screen-files (:uri req))]
    {:status 200 :headers {"Content-Type" ct "Cache-Control" "max-age=86400"}
     :body (io/file base-dir f)}
    (handler-3 req)))

(defn handler-5
  "handler-4, plus /share: the QR code page the TV shows for Share."
  [req]
  (if (= "/share" (:uri req))
    (let [a (:a (query req))]
      (if (re-matches #"[\w.\-]+(:\d+)?" (or a ""))
        {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body (share-html a)}
        {:status 400 :body "bad address"}))
    (handler-4 req)))

(defn -main []
  (server/run-server handler-5 {:port port :ip "0.0.0.0" :thread 8})
  (println "tvremote on port" port)
  (reset! last-saved (:watched (load-state) {}))
  (reset! last-saved-2 (into {} (for [[user m] (:watched-by (load-state))
                                      [svc w] m]
                                  [[user svc] w])))
  (reset! owner (:owner (load-state) {}))
  (reset! opened (:opened (load-state) {}))
  (reset! rv-id (:rv-id (load-state)))
  (reset! rl-id (:rl-id (load-state)))
  (reset! page-id (:page-id (load-state)))
  (try (adopt-front-tab!) (catch Exception e (println "adopt-front-tab:" (ex-message e))))
  (future (loop []
            (Thread/sleep 15000)
            (try (when (chrome-up?) (record-watching-2!))
                 (catch Exception e (println "record-watching:" (ex-message e))))
            (recur)))
  @(promise))

(when (= *file* (System/getProperty "babashka.file")) (-main))
