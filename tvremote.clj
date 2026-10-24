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

;; A :people entry is a name, or a map {:name "Chris" :reading? true}
;; (2026-10-16). Reading is offered only to those with :reading?, or to
;; everyone when no one has it.
(def people-names (mapv #(if (map? %) (:name %) %) (:people config)))
(def reading-people (vec (keep #(when (and (map? %) (:reading? %)) (:name %)) (:people config))))
;; :photos? the same way (2026-10-16), but with no one marked no one gets it.
(def photos-people (vec (keep #(when (and (map? %) (:photos? %)) (:name %)) (:people config))))
;; :keep? false / :not-interested? false (2026-10-21) take Keep this / Not
;; interested away from that person; unmarked, everyone has them.
(def no-keep-people (vec (keep #(when (and (map? %) (false? (:keep? %))) (:name %)) (:people config))))
(def no-nope-people (vec (keep #(when (and (map? %) (false? (:not-interested? %))) (:name %)) (:people config))))
(defn barred? [names user]
  (boolean (some #(= user (str/lower-case (str/replace % #"[^A-Za-z]" ""))) names)))

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

(def nf-last-js
  "True when what's playing is a film, or the last episode of the last season:
   after it there's nothing more to watch (2026-10-22, for Recent)."
  (str "(()=>{try{const p=" player-js ";if(!p)return null;const mid=p.getMovieId();
   const m=netflix.appContext.state.playerApp.getState().videoPlayer.videoMetadata[mid];
   const v=m&&m._metadataObject&&m._metadataObject.video;if(!v)return null;
   const ss=v.seasons||[];if(!ss.length)return true;
   const eps=ss[ss.length-1].episodes||[];const e=eps[eps.length-1];
   return !!e&&(e.id===mid||e.episodeId===mid)}catch(e){return null}})()"))

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

(def yt-upnext-js
  "yt-scrape-js on the side list of the playing video only (Up next, 2026-10-21)."
  (-> yt-scrape-js
      (str/replace "document.querySelectorAll('a[href" "document.querySelectorAll('#secondary a[href")
      (str/replace "{title:document.title.replace(/ - YouTube$/,''),items:items}" "{title:'Up next',items:items}")))

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

(def yt-count-js "document.querySelectorAll('a[href*=\"/watch?v=\"]').length")

(defn yt-browse-2
  "As yt-browse, but gentler: 2 scrolls, not 4, and each waits until the
   new videos are in (up to 5s) before the next. 2026-10-19: four quick
   scrolls on Subscriptions froze the YouTube tab on this old machine."
  [url]
  (reset! service "yt")
  (let [t (yt-target)]
    (nf-pause)
    (navigate t url)
    (Thread/sleep 1000)
    (case (wait-for t yt-kind-js 15000)
      "titles" (do (dotimes [_ 2]
                     (let [before (js t yt-count-js)]
                       (js t "window.scrollTo(0,document.documentElement.scrollHeight)")
                       (loop [n 0]
                         (Thread/sleep 700)
                         (when (and (< n 7) (<= (or (js t yt-count-js) 0) (or before 0)))
                           (recur (inc n))))
                       (Thread/sleep 500)))
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

(def page-links-js-3
  "page-links-js-2, with a link that has no text of its own (an icon-only link
   beside a heading, as GitHub and many doc sites have) labelled by its heading:
   the heading it's in or next to, else the element it jumps to, else its
   aria-label (2026-10-11)."
  (str/replace page-links-js-2 "text:(a.textContent.trim().replace(/\\s+/g,' ')||a.title||u)"
               "text:(a.textContent.trim().replace(/\\s+/g,' ')||(()=>{const H='h1,h2,h3,h4,h5,h6',h=a.closest(H)||(a.parentElement&&a.parentElement.querySelector(H)),t=parts[1]&&(document.getElementById(decodeURIComponent(parts[1]))||document.getElementById('user-content-'+decodeURIComponent(parts[1])));return ((h||t)?(h||t).textContent:'').trim().replace(/\\s+/g,' ')})()||(a.getAttribute('aria-label')||'').replace(/^Permalink:\\s*/,'')||a.title||u)"))

(def page-links-js-4
  "page-links-js-3, then the site's own header links (a blog's Archive, Tags,
   Feed) after the page's, marked :site so the remote can show them under a
   divider. Same-site only and at most 8, so a big site's menus can't flood the
   list (2026-10-13)."
  (str/replace page-links-js-3 "return out})()"
               "let n=0;document.querySelectorAll('header a[href],nav a[href],.site-header a[href],a.page-link[href]').forEach(a=>{
     const u=a.href;if(n>=8||seen.has(u)||u.split('#')[0]===here||a.origin!==location.origin)return;
     const t=(a.textContent.trim().replace(/\\s+/g,' ')||a.title||a.getAttribute('aria-label')||'').slice(0,120);if(!t)return;
     seen.add(u);n++;out.push({url:u,jump:false,site:true,text:t})});
   return out})()"))

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
                  m  (assoc :title (:title m) :episode (:episode m) :show (:show m))
                  ps (assoc :profiles (vec ps))))
              {:chrome true :service "nf" :away true})))))

;; status, plus a link from Reading — read from Chrome's target list only.
(defn yt-done?
  "A YouTube history entry watched to the end: within 30s or 5% of it, the
   start-over-if-done! rule. Unknown length (saved before 2026-10-22): no."
  [{:keys [time-s dur-s]}]
  (boolean (and time-s dur-s (pos? dur-s)
                (or (>= time-s (- dur-s 30)) (>= time-s (* 0.95 dur-s))))))

(def yt-category-js
  "The playing video's YouTube category (\"Music\", \"News & Politics\", ...)."
  "try{return ok?p.getPlayerResponse().microformat.playerMicroformatRenderer.category:null}catch(e){return null}")

(defn music? [w] (= "Music" (:category w)))

(defn nf-done?
  "A Netflix history entry with nothing more to watch: a film or a show's last
   episode, within 30s or 5% of its end. Saved before 2026-10-22: no."
  [w]
  (boolean (and (:last? w) (yt-done? w))))

(defn shown-history
  "user's history for k (:nf :yt :page) as Recent shows it: without what was
   ✕'d (:hidden) or watched to the end. Those stay in the history, so Up next
   still leaves them out (2026-10-22). Recent and resume! both use this, so a
   tap's index points at the line the phone shows."
  [user k]
  (cond->> (remove :hidden (get-in (load-state) [:history user k]))
    ;; music is for hearing again: a favourite stays (2026-10-23)
    (= k :yt) (remove #(and (yt-done? %) (not (music? %))))
    (= k :nf) (remove nf-done?)))

(defn recent
  "The first 10 of user's history for the service svc shows under."
  [user svc]
  (when-let [k (and user ({"nf" :nf "yt" :yt "page" :page "rv" :page "rl" :page} svc))]
    (vec (for [w (take 10 (shown-history user k))]
           {:title (or (:title w) (:url w) (:id w))
            :key   (:key w)
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
;; video, a page URL. The phone shows the first 10 of the current service.

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
            (let [m (cond-> {:url u :title title :time-s sec}
                      ;; length and last-or-film, so Recent can leave out a finished show
                      (some-> (:duration s) pos?) (assoc :dur-s (long (/ (:duration s) 1000))
                                                         :last? (true? (try (js n nf-last-js) (catch Exception _ nil)))))]
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
            (let [m (cond-> {:id id :title (:title s) :channel (:picked s) :time-s sec}
                      ;; its length, so Recent can leave out what was watched to the end
                      (some-> (:duration s) pos?) (assoc :dur-s (long (/ (:duration s) 1000)))
                      ;; YouTube's category: "Music" is kept on Recent and in Up next
                      true (assoc :category (try (yt-do y yt-category-js) (catch Exception _ nil))))]
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

;; Back to the start (2026-10-13): a song came back at its last few seconds.
;; A video under 10 minutes always starts again, and so does any video within
;; 30s of its end or past 95% of it; a long one part-way through keeps its
;; place. Decided in the tab, from the player's own duration, so records saved
;; before this work too. Not during an ad (its duration isn't the video's).

(def yt-start-over-js
  "(()=>{const v=document.querySelector('video'),p=document.getElementById('movie_player');
     if(!v||!(v.duration>0)||(p&&p.classList.contains('ad-showing')))return null;
     const d=v.duration,t=v.currentTime;
     if(d<600||d-t<30||t>0.95*d){v.currentTime=0;return true}return false})()")

(defn start-over-if-done!
  "In tab t, send a short or nearly finished video back to 0:00 (waits up to
   ~5s for the player to know its duration)."
  [t]
  (loop [n 0]
    (let [r (try (js t yt-start-over-js) (catch Exception _ nil))]
      (when (and (nil? r) (< n 10))
        (Thread/sleep 500)
        (recur (inc n))))))

;; No chat replay (2026-10-15): a long video with a recorded live stream
;; opens YouTube's "Top chat replay" beside the player, even full screen,
;; shrinking the video and showing strangers' chat to whoever is watching.
;; YouTube's own hide button closes it and the player fills the screen again;
;; a loop presses it within a few seconds of it appearing (watch-yt-chat!).

(def yt-hide-chat-js
  "(()=>{const f=document.querySelector('ytd-watch-flexy');
     if(!f||!f.hasAttribute('live-chat-present-and-expanded'))return false;
     const b=document.querySelector('ytd-live-chat-frame #show-hide-button button');
     if(b){b.click();return true}return false})()")

(defn hide-yt-chat!
  "Close YouTube's chat replay in any YouTube watch tab (the main one or
   Reading's video tab) where it's open. Never opens a tab."
  []
  (doseq [t (filter #(and (on-site? "youtube.com" %) (str/includes? (str (:url %)) "/watch"))
                    (targets))]
    (try (js t yt-hide-chat-js) (catch Exception _ nil))))

(defn load-yt!
  "Load YouTube watch record w in tab t, held paused at its second — or at the
   start, for a short or nearly finished video (start-over-if-done!)."
  [t w]
  (cdp t "Page.navigate" {:url (str yt "/watch?v=" (:id w) "&t=" (:time-s w 0) "s")})
  (hold-paused! t yt-pause-js yt-paused-js)
  (start-over-if-done! t))

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
  (if-let [w (and user (nth (vec (shown-history user (keyword svc))) i nil))]
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

(defn forget!
  "Hide the entry keyed k from user's recent svc (the phone's ✕, 2026-10-12;
   hidden rather than removed since 2026-10-22, so Up next still knows it).
   Watching it again brings it back.
   Only the list changes: nothing on the TV stops, and Netflix's and YouTube's
   own histories are left alone. If it's the item they opened, it stops being
   theirs too, or the 15s check would put it straight back."
  [svc user k]
  (if (and user k (#{"nf" "yt" "page"} svc))
    (do (when (= {:user user :key k} (get @opened svc))
          (swap! opened dissoc svc))
        (update-state! (fn [st] (-> st
                                    ;; hidden, not removed: Up next still leaves it out (2026-10-22)
                                    (update-in [:history user (keyword svc)]
                                               #(mapv (fn [w] (cond-> w (= k (:key w)) (assoc :hidden true))) %))
                                    (assoc :opened @opened))))
        {:ok true})
    {:ok false :error "Nothing to remove"}))

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

;; ---------------------------------------------------------------- input lock
;; Optional (config :input-lock true): the phone can lock the TV computer's own
;; keyboards, mice and touchpads, so a child's presses there do nothing.
;; inputlock.py holds the devices while it runs; ending it unlocks. The power
;; button is set to do nothing while locked (it used to suspend the machine).

(def input-lock? (boolean (:input-lock config)))
(def unlock-word (str/lower-case (or (:unlock-word config) "tvremote")))
(def lock-proc (atom nil))
(def lock-error (atom nil))

(defn locked? [] (boolean (some-> @lock-proc :proc .isAlive)))

(defn power-button
  "The power button's action (GNOME), or set it to action."
  ([] (try (str/replace (str/trim (:out (p/shell {:out :string :err :string}
                                                  "gsettings" "get" "org.gnome.settings-daemon.plugins.power"
                                                  "power-button-action"))) "'" "")
           (catch Exception _ nil)))
  ([action] (try (p/shell {:out :string :err :string} "gsettings" "set"
                          "org.gnome.settings-daemon.plugins.power" "power-button-action" action)
                 (catch Exception _ nil))))

(defn- lock-ended!
  "The lock is off: remember that, and give the power button back."
  []
  (update-state! #(dissoc % :input-locked))
  (when-let [was (:power-button-was (load-state))]
    (power-button was)
    (update-state! #(dissoc % :power-button-was))))

(defn lock-input! []
  (when (and input-lock? (not (locked?)))
    (reset! lock-error nil)
    (let [pr (p/process {:out :string :err :string}
                        "python3" (str base-dir "/inputlock.py") unlock-word)]
      (reset! lock-proc pr)
      (when-not (:power-button-was (load-state))
        (when-let [was (power-button)]
          (when (not= was "nothing")
            (update-state! #(assoc % :power-button-was was)))))
      (power-button "nothing")
      (update-state! #(assoc % :input-locked true))
      ;; it ends by itself when the word is typed, or fails without permission
      (future (let [r @pr]
                (when (identical? pr @lock-proc)
                  (reset! lock-proc nil)
                  (when (not= 0 (:exit r))
                    (reset! lock-error (str/trim (str (:err r))))
                    (println "input lock:" @lock-error))
                  (lock-ended!))))
      (Thread/sleep 500)
      (if (locked?) {:ok true} {:ok false :error (or @lock-error "The lock didn't start")}))))

(defn unlock-input! []
  (when-let [pr @lock-proc]
    (reset! lock-proc nil)
    (p/destroy pr))
  (lock-ended!)
  {:ok true})

(defn input-lock-status []
  (when input-lock? {:locked (locked?) :error @lock-error}))

;; ---------------------------------------------------------------- used at the TV
;; Whether someone used the TV computer's own keyboard or mouse (2026-10-13),
;; so the phone can tell a video picked at the TV from YouTube's autoplay.
;; inputwatch.py only listens (no grab) and prints a line per use; it runs
;; whenever the lock is available (:input-lock), since it needs the same
;; 'input' group. last-press is the latest phone press (handler-8).

(def last-touch "When the keyboard or mouse was last used, ms, or nil." (atom nil))
(def last-press "When a phone last pressed something, ms, or nil." (atom nil))

(defn watch-input!
  "Run inputwatch.py for good, restarting it 30s after it ends."
  []
  (future
    (loop []
      (try (let [pr (p/process {:err :string} "python3" (str base-dir "/inputwatch.py"))]
             (doseq [_ (line-seq (io/reader (:out pr)))]
               (reset! last-touch (System/currentTimeMillis)))
             (let [r @pr] (println "input watch ended:" (:exit r) (str/trim (str (:err r))))))
           (catch Exception e (println "input watch:" (ex-message e))))
      (Thread/sleep 30000)
      (recur))))

;; Still watching? (2026-10-15): after a while with nobody touching anything,
;; Netflix pauses with "Are you still watching?" and a Continue watching
;; button. The tick presses it the first time; the next time, with no phone
;; press or TV keyboard/mouse use since, it is left up and the phone shows it
;; with a Continue button instead. So a show never stops on the first, but
;; also never plays all night to an empty room. The button is found by its
;; text (or Netflix's data-uia); the log says what it was each time.

(def nf-still-js
  "(press=>{const b=document.querySelector('[data-uia=\"interrupt-autoplay-continue\"]')
     ||[...document.querySelectorAll('button')].find(e=>e.offsetParent&&/^\\s*continue (watching|playing)/i.test(e.innerText));
   if(!b)return null;const r={text:b.innerText,uia:b.getAttribute('data-uia')};
   if(press)b.click();return r})(%s)")

(def nf-still
  "Netflix's Still watching? pause: :up while it waits for a phone, :auto-at
   when the tick last pressed Continue itself, ms."
  (atom {}))

(defn nf-watch-tab []
  (first (filter #(and (on-site? "netflix.com" %) (str/includes? (str (:url %)) "/watch"))
                 (targets))))

(defn nf-continue!
  "Press Netflix's Continue watching (the phone's button)."
  []
  (if-let [seen (some-> (nf-watch-tab) (js (format nf-still-js "true")))]
    (do (swap! nf-still assoc :up false) {:ok true :pressed (:text seen)})
    {:ok false :error "Netflix isn't asking now"}))

(defn nf-still-watching!
  "For the tick: deal with Netflix's Still watching? pause, if it's up."
  []
  (let [n    (nf-watch-tab)
        seen (when n (js n (format nf-still-js "false")))
        {:keys [auto-at up]} @nf-still
        since (max (or @last-press 0) (or @last-touch 0))]
    (cond
      (not seen) (when up (swap! nf-still assoc :up false))
      (or (nil? auto-at) (> since auto-at))
      (do (js n (format nf-still-js "true"))
          (swap! nf-still assoc :up false :auto-at (System/currentTimeMillis))
          (println "Netflix still watching?: pressed" (pr-str seen) "itself"))
      (not up)
      (do (swap! nf-still assoc :up true)
          (println "Netflix still watching?: second time with nobody about, left for a phone" (pr-str seen))))))

(def nf-dialog-js
  "Netflix's front pop-up with buttons, if any: %s is nil to read it, or a
   button's index to press it."
  "(press=>{const vis=e=>e&&e.getClientRects().length>0;
   const ds=[...document.querySelectorAll('[role=dialog],[role=alertdialog],[data-uia*=interrupt],[data-uia*=modal],[data-uia*=prompt]')]
     .filter(d=>vis(d)&&d.querySelector('button'));
   const d=ds[ds.length-1];if(!d)return null;
   const bs=[...d.querySelectorAll('button')].filter(b=>vis(b)&&(b.innerText||'').trim()).slice(0,4);
   if(press!==null){const b=bs[press];if(!b)return false;b.click();return true}
   if(!bs.length)return null;
   return {text:(d.innerText||'').trim().replace(/\\s+/g,' ').slice(0,160),uia:d.getAttribute('data-uia'),
           buttons:bs.map(b=>b.innerText.trim().slice(0,40))}})(%s)")

(def nf-dialog
  "Netflix's pop-up the phone shows (2026-10-18): {:id tab :text :buttons}, or
   nil. Still watching? has its own handling; this is for the others, like
   the 'continue with Landman?' that needed the TV's mouse on 2026-10-17."
  (atom nil))

(defn nf-dialog-tick!
  "For the tick: notice a Netflix pop-up, log it once, offer it to the phones."
  []
  (let [t (when (and (= "nf" @service) (not (:up @nf-still)))
            (first (filter #(and (= "page" (:type %)) (on-site? "netflix.com" %)) (targets))))
        d (when t (js t (format nf-dialog-js "null")))]
    (if d
      (do (when (not= (:text d) (:text @nf-dialog))
            (println "Netflix asks:" (pr-str d)))
          (reset! nf-dialog (assoc d :id (:id t))))
      (reset! nf-dialog nil))))

(defn nf-dialog-press!
  "A phone pressed button i of Netflix's pop-up."
  [i]
  (if-let [t (some->> (:id @nf-dialog) (#(first (filter (fn [x] (= % (:id x))) (targets)))))]
    (let [ok (js t (format nf-dialog-js (str (long i))))]
      (println "Netflix asks: pressed" i (get-in @nf-dialog [:buttons i]))
      (reset! nf-dialog nil)
      (if ok {:ok true} {:ok false :error "That button has gone"}))
    {:ok false :error "Netflix isn't asking anything now"}))

(defn local-status
  "For /status: :touched-ago (ms since the keyboard or mouse was used, when
   that was after the last phone press); :by-itself when YouTube or Netflix
   has moved off what a phone opened; :keepable when what's playing there is
   no phone's (Keep this); :nf-still when Netflix's Still watching? waits for
   a phone."
  [st]
  (let [t @last-touch now (System/currentTimeMillis)
        id (some->> (:url st) (re-find #"[?&]v=([\w-]{11})") second)
        k  (get-in @opened ["yt" :key])]
    (cond-> {}
      (:up @nf-still) (assoc :nf-still true)
      @nf-dialog (assoc :nf-dialog (dissoc @nf-dialog :id))
      (and t (or (nil? @last-press) (> t @last-press))) (assoc :touched-ago (- now t))
      (and (= "yt" (:service st)) id k (not= id k)) (assoc :by-itself true)
      (and (= "yt" (:service st)) id (not= id k))   (assoc :keepable true)
      (and (= "nf" (:service st)) (:show st)
           (not= (:show st) (get-in @opened ["nf" :key]))) (assoc :keepable true)
      (and (= "nf" (:service st)) (:show st) (get-in @opened ["nf" :key])
           (not= (:show st) (get-in @opened ["nf" :key]))) (assoc :by-itself true))))

(defn shut-down!
  "Power the media machine off (the phone's Shut it down, 2026-10-15; its
   power button does nothing). A second's wait lets the answer reach the
   phone first. Allowed without a password: the service runs in the logged-in
   user's manager, and logind's CanPowerOff says yes there."
  []
  (println "shutting down, from a phone")
  (future (Thread/sleep 1000)
          (try (p/shell "systemctl" "poweroff")
               (catch Exception e (println "shut down:" (ex-message e)))))
  {:ok true})

(defn keep!
  "Make what's playing on svc (\"yt\" or \"nf\") user's, as if they had opened
   it from the phone (Keep this, 2026-10-14): it goes into their Recent and
   its place is saved from now on. For something that came on by itself or
   was picked at the TV, which otherwise only a search could reach. Netflix is
   kept by show, like a phone-opened show, so its next episodes follow."
  [svc user]
  (let [[t js-s] (case svc
                   "yt" [(yt-target) yt-status-js]
                   "nf" [(first (filter (partial on-site? "netflix.com") (targets))) nil]
                   nil)
        k (case svc
            "yt" (some->> (some-> t (yt-do js-s) :url) (re-find #"[?&]v=([\w-]{11})") second)
            "nf" (when (and t (str/includes? (:url t) "/watch"))
                   (:show (try (js t nf-meta-js) (catch Exception _ nil))))
            nil)]
    (if (and user k)
      (do (set-owner! svc user)
          (set-opened! svc user k)
          (record-watching-2!)
          {:ok true :kept (or (:title (first (get-in (load-state) [:history user (keyword svc)]))) k)})
      {:ok false :error "Nothing playing to keep"})))

;; Next and Not interested (2026-10-21): beside Keep this, for a YouTube video
;; no phone opened. Press Next a few times, then Keep this. Not interested is
;; for kids' videos: it tells YouTube (the shared account, so the whole house).

(def yt-next-js
  "YouTube's own next button: the video it would autoplay."
  "if(!ok)return false;const b=document.querySelector('.ytp-next-button');
   if(b&&b.href){b.click();return true}p.nextVideo();return true")

(def yt-nope-js
  "Dislike the playing video, press Don't recommend channel on a video of the
   same channel in the side list (the playing one has no such menu). Returns
   what was done; yt-nope! then goes to Home. Not the side list or YouTube's
   next button: next to a kids' video both are all kids' videos (2026-10-21:
   Next gave three more Ms Rachel, the side list ten more kids' channels)."
  "(async()=>{const sl=ms=>new Promise(r=>setTimeout(r,ms));
   const p=document.getElementById('movie_player');
   if(!(p&&p.getPlayerState&&location.pathname==='/watch'))return {ok:false};
   const own=document.querySelector('ytd-watch-metadata ytd-channel-name a');
   const name=own&&own.innerText.trim();
   const d=document.querySelector('dislike-button-view-model button');
   let disliked=false;
   if(d){if(d.getAttribute('aria-pressed')!=='true')d.click();disliked=true}
   let channel=false;
   const tile=name&&[...document.querySelectorAll('#secondary yt-lockup-view-model')]
     .find(t=>t.innerText.includes(name));
   const mb=tile&&tile.querySelector('button[aria-label*=\"More actions\"]');
   if(mb){mb.click();
     for(let i=0;i<15&&!channel;i++){await sl(200);
       const it=[...document.querySelectorAll('yt-list-item-view-model, ytd-menu-service-item-renderer')]
         .find(e=>e.offsetParent&&e.innerText.trim()===\"Don't recommend channel\");
       if(it){it.click();channel=true}}
     if(!channel)document.body.click()}
   return {ok:true,channel:name,disliked,blocked:channel}})()")

(def yt-home-top-js
  "Click a random one of YouTube Home's first 24 videos, skipping channel %s and
   the video ids %s (both JSON). Random, not the top one: the top of Home is
   music mixes and barely changes between presses, so the top tile cycled a
   few videos and never reached the geopolitics further down (2026-10-21).
   Returns {:id :title}, or null while Home hasn't drawn."
  "(()=>{const name=%s,seen=%s;
   const ts=[...document.querySelectorAll('ytd-rich-item-renderer yt-lockup-view-model')].slice(0,24)
     .map(r=>({r,a:r.querySelector('a[href*=\"/watch?v=\"]')})).filter(x=>x.a)
     .map(x=>Object.assign(x,{id:(x.a.getAttribute('href').match(/v=([\\w-]{11})/)||[])[1]}));
   if(!ts.length)return null;
   const ok=ts.filter(x=>!(name&&x.r.innerText.includes(name))&&!seen.includes(x.id));
   const pick=(ok.length?ok:ts)[Math.floor(Math.random()*(ok.length?ok:ts).length)];
   pick.a.click();return {id:pick.id,title:(pick.r.querySelector('h3')||pick.a).innerText.trim()}})()")

;; Videos Not interested has played or dismissed, so a press never offers one
;; again; forgotten after 6 hours.
(def yt-offered (atom {}))

(defn yt-offered-ids []
  (let [cut (- (System/currentTimeMillis) (* 6 3600 1000))]
    (keys (swap! yt-offered #(into {} (filter (fn [[_ t]] (> t cut))) %)))))

(defn yt-next! []
  (if-let [t (yt-target)]
    {:ok (boolean (yt-do t yt-next-js))}
    {:ok false :error "No YouTube tab"}))

(defn yt-nope! []
  (if-let [t (yt-target)]
    (let [r (js t yt-nope-js)]
      (if-not (:ok r)
        {:ok false :error "YouTube isn't playing a video"}
        ;; then YouTube's top Home suggestion, which the cleaned history now steers
        (let [now-id (some->> (:url t) (re-find #"[?&]v=([\w-]{11})") second)]
            (when now-id (swap! yt-offered assoc now-id (System/currentTimeMillis)))
            (js t (str "location.href='" yt "/'"))
            (Thread/sleep 1500)
            (let [top (wait-for t (format yt-home-top-js (json/generate-string (:channel r))
                                          (json/generate-string (vec (yt-offered-ids)))) 12000)
                  _ (when-let [id (:id top)] (swap! yt-offered assoc id (System/currentTimeMillis)))
                  r (assoc r :next (:title top))]
              (println "yt not interested:" (pr-str r))
              r))))
    {:ok false :error "No YouTube tab"}))

(def repo-url "https://github.com/chrismurrph/tvremote")

(defn share-html
  "The page the TV shows for Share: two QR codes for a phone's camera. One is
   this TV's remote (addr, as the pressing phone saw it), with the names it
   knows; the other is the project on GitHub, for someone's own TV."
  [addr]
  (let [ppl (seq people-names)
        esc #(str/escape (str %) {\< "&lt;" \> "&gt;" \& "&amp;" \' "&#39;" \" "&quot;"})]
    (str "<!doctype html><html><head><meta charset=utf-8><title>Share the TV remote</title>
<script src='https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js'></script>
<style>body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;
background:#141414;color:#eee;font:24px/1.35 system-ui,sans-serif;text-align:center}
.row{display:flex;gap:90px;align-items:flex-start}.col{width:440px}
h1{font-size:40px;font-weight:600;margin:0 0 26px}h2{font-size:32px;margin:0 0 6px}
.qr{display:inline-block;background:#fff;padding:18px;border-radius:14px;margin:14px 0}
small{display:block;color:#999;font-size:20px}footer{color:#999;font-size:20px;margin-top:22px}</style></head>
<body><h1>📱 Scan with your phone's camera</h1><div class=row>
<div class=col><h2>Use this TV's remote</h2><small>On this home's Wi-Fi only</small>
<div class=qr id=lan></div><div>http://" addr "/</div>"
         (when ppl
           (str "<small>Names on this TV: " (esc (str/join ", " ppl))
                ". Not there? Ask whoever set the TV up to add your name.</small>"))
         "</div>
<div class=col><h2>Get it for your own TV</h2><small>Free, on GitHub</small>
<div class=qr id=gh></div><div>" (str/replace repo-url "https://" "") "</div>
<small>Needs a spare computer plugged into the TV.</small></div></div>
<footer>This goes away in 2 minutes.</footer>
<script>new QRCode(document.getElementById('lan'),{text:'http://" addr "/',width:300,height:300});
new QRCode(document.getElementById('gh'),{text:'" repo-url "',width:300,height:300})</script>
</body></html>")))

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

(def awake-proc "The gnome-session-inhibit holding the screen on, or nil." (atom nil))

(defn keep-awake! [on?]
  (let [pr @awake-proc alive (and pr (.isAlive (:proc pr)))]
    (cond
      (and on? (not alive))
      (do (reset! awake-proc
                  (p/process {:out :discard :err :discard
                              :extra-env {"XDG_RUNTIME_DIR" runtime-dir
                                          "DBUS_SESSION_BUS_ADDRESS" (str "unix:path=" runtime-dir "/bus")}}
                             "gnome-session-inhibit" "--inhibit" "idle" "--reason" "tvremote: watching"
                             "sleep" "infinity"))
          (println "keep-awake: on"))
      (and (not on?) alive)
      (do (p/destroy-tree (:proc pr)) (reset! awake-proc nil) (println "keep-awake: off")))))

(defn act [action q]
  (wake-screen!)
  ;; before GNOME's re-blank 15s after a wake with no real input (2026-10-22)
  (try (keep-awake! true) (catch Exception _ nil))
  (if (and (not (chrome-up?)) (not (#{"start" "close-all" "input-lock"} action)))
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
        "forget"     (forget! (:svc q) (user-of q) (:key q))
        "keep"       (if (barred? no-keep-people (user-of q))
                       {:ok false :error "Keep this is turned off for you"}
                       (keep! (:svc q) (user-of q)))
        "yt-next"    (yt-next!)
        "yt-nope"    (if (barred? no-nope-people (user-of q))
                       {:ok false :error "Not interested is turned off for you"}
                       (yt-nope!))
        "shutdown"   (shut-down!)
        "nf-continue" (nf-continue!)
        "tabs"       {:ok true :tabs (user-tabs)}
        "share"      (share! (:host q))
        "share-off"  (share-off!)
        "input-lock" (cond (not input-lock?) {:ok false :error "The lock is off in config.edn (:input-lock)"}
                           (= "1" (:on q)) (lock-input!)
                           :else (unlock-input!))
        "tab"        {:ok (boolean (switch-tab-2 (:id q) (user-of q)))}
        "tab-close"  {:ok (close-tab (:id q))}
        "page-scroll" (if-let [p (reading-target)] {:ok (page-scroll p (:how q))} {:ok false :error "No page open for reading"})
        "page-videos" (if-let [p (page-target)] {:ok true :videos (js p page-videos-js)} {:ok false :error "No page open for reading"})
        ;; ignoreCache so a just-republished page really is fetched again;
        ;; Chrome keeps the scroll position across a reload.
        ;; Chrome's own restore puts back the pixel offset before the text size
        ;; is reapplied, which lands higher up the page; the place is kept as a
        ;; fraction of the way down instead, and put back after the size.
        "page-reload" (if-let [p (reading-target)]
                          (let [frac (or (:scroll (js p page-place-js)) 0)]
                            (cdp p "Page.reload" {:ignoreCache true})
                            (Thread/sleep 500)
                            (when (wait-for p "document.readyState==='complete'" 15000)
                              (set-zoom-2 p "keep" (get @owner "page") (= (:id p) @rl-id))
                              (Thread/sleep 300)
                              (js p (str "(()=>{const h=document.documentElement.scrollHeight-innerHeight;
                                          scrollTo(0," frac "*h);return true})()")))
                            {:ok true})
                          {:ok false :error "No page open for reading"})
        "page-zoom"  (if-let [p (reading-target)] (assoc (set-zoom-2 p (:how q) (or (user-of q) (get @owner "page")) (= (:id p) @rl-id)) :ok true) {:ok false :error "No page open for reading"})
        "page-links" (if-let [p (reading-target)] {:ok true :links (js p page-links-js-4)} {:ok false :error "No page open for reading"})
        "page-link"  (do (set-owner! "page" (user-of q))
                         (let [ok (rl-open (:url q))]
                           (when ok (some-> (rl-target) zoom-when-loaded!))
                           {:ok ok}))
        "crumb"      (let [ok (boolean (rl-goto (parse-long (:id q))))]
                       (when ok (some-> (rl-target) zoom-when-loaded!))
                       {:ok ok})
        "page-back"  (if-let [p (page-target)] {:ok (page-back p)} {:ok false :error "No page open for reading"})
        "yt-home"    (assoc (yt-browse-2 (str yt "/")) :ok true)
        "yt-subs"    (assoc (yt-browse-2 (str yt "/feed/subscriptions")) :ok true)
        "yt-history" (assoc (yt-browse-2 (str yt "/feed/history")) :ok true)
        ;; newest first: YouTube adds each save at the bottom (2026-10-25)
        "yt-later"   (-> (yt-browse-2 (str yt "/playlist?list=WL"))
                         (update :rows (fn [rs] (mapv #(update % :items (comp vec reverse)) rs)))
                         (assoc :ok true))
        ;; the TV's right-hand list, read without leaving the video
        "yt-upnext"  (let [t (yt-target)
                           rows (when (and t (str/includes? (:url t) "/watch")) (js t yt-upnext-js))
                           now-id (some->> t :url (re-find #"[?&]v=([\w-]{11})") second)
                           ;; and what this person already watched (their Recent history)
                           seen (into #{now-id} (comp (remove music?) (map :id))
                                      (get-in (load-state) [:history (user-of q) :yt]))
                           rows (mapv (fn [r] (update r :items (fn [is] (vec (remove #(seen (:id %)) is))))) rows)]
                       (if (seq rows) {:ok true :kind "titles" :rows rows}
                           {:ok false :error "Nothing playing on YouTube, so no Up next list"}))
        "yt-search"  (assoc (yt-browse-2 (str yt "/results?search_query=" (java.net.URLEncoder/encode (:q q) "UTF-8"))) :ok true)
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
        (= uri "/status") (reply (cond-> (status-2 (user-of (query req))) @share-id (assoc :sharing true) input-lock? (assoc :lock (input-lock-status)) input-lock? (as-> st (merge st (local-status st)))))
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
  "handler, logging every button press to the journal: who, from which phone
   (its address), what, how long, and the error if it failed. /status (polled every 3s) is not logged."
  [req]
  (if-not (str/starts-with? (:uri req) "/act/")
    (handler req)
    (let [t0   (System/currentTimeMillis)
          q    (query req)
          res  (handler req)
          ms   (- (System/currentTimeMillis) t0)
          out  (try (json/parse-string (:body res) true) (catch Exception _ nil))
          args (for [[k v] (dissoc q :u)] (str (name k) "=" (subs v 0 (min 80 (count v)))))]
      (println (str/join " " (concat [(or (:u q) "-") (str "@" (:remote-addr req)) (subs (:uri req) 5)] args
                                     [(str ms "ms") (if (:ok out) "ok" (str "FAILED: " (:error out)))])))
      (flush)
      res)))

(defn handler-3
  "handler-2, plus /config: the names the phone offers (the :people setting),
   who gets Reading (empty: everyone) and who gets Photos (empty: no one)."
  [req]
  (if (= "/config" (:uri req))
    (reply {:people people-names :reading reading-people :photos photos-people
            :no-keep no-keep-people :no-nope no-nope-people})
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

(defn last-svc-of
  "Which service a successful press shows the presser using, or nil."
  [action q]
  (case action
    "play" "nf"
    "yt-play" "yt"
    ("open" "page-link" "crumb" "page-video") "page"
    "switch" (#{"nf" "yt" "page"} (:to q))
    "resume" (#{"nf" "yt" "page"} (:svc q))
    "keep" (#{"nf" "yt"} (:svc q))
    nil))

(defn handler-6
  "handler-5, plus Take over resuming the presser's last thing: each press
   that shows them using a service is remembered (state.edn :last-svc
   {user svc}); Start then switches to that service, which loads their own
   last show, video or page. Netflix opens on \"Who's watching?\" after a
   start, so for Netflix it waits for the profile pick instead."
  [req]
  (let [res (handler-5 req)]
    (when (str/starts-with? (:uri req) "/act/")
      (let [action (subs (:uri req) 5)
            q      (query req)
            user   (user-of q)
            ok     (:ok (try (json/parse-string (:body res) true) (catch Exception _ nil)))
            svc    (and user (get-in (load-state) [:last-svc user]))]
        (when (and ok user)
          (try
            (when-let [s (last-svc-of action q)]
              (when (not= s svc) (update-state! #(assoc-in % [:last-svc user] s))))
            (cond
              (and (= action "start") (#{"yt" "page"} svc)) (switch-service-2! svc user)
              (and (= action "profile") (= "nf" svc))     (switch-service-2! "nf" user))
            (catch Exception e (println "resume last:" (ex-message e)))))))
    res))

(defn volume-floor!
  "Whatever is now playing starts no quieter than mid-range: below 30% it is
   set to 50%. Netflix and YouTube each remember their own volume, and
   YouTube's had drifted to 5% (2026-10-12), so a video seemed not to play."
  []
  (case @service
    "nf" (some-> (tv-target) (player-do "if(p&&p.getVolume()<0.3){p.setVolume(0.5);return true}return false"))
    "yt" (some-> (yt-target) (yt-do "if(ok&&p.getVolume()<30){p.unMute();p.setVolume(50);return true}return false"))
    "rv" (some-> (rv-target) (yt-do "if(ok&&p.getVolume()<30){p.unMute();p.setVolume(50);return true}return false"))
    nil))

(def person-volume
  "Each person's own volume, 0-1: where their last Vol press left it
   (state.edn :volume {name v})."
  (atom {}))

(defn current-volume
  "The current player's volume, 0-1, or nil."
  []
  (case @service
    "nf" (some-> (tv-target) (player-do "return p?p.getVolume():(v?v.volume:null)"))
    "yt" (some-> (yt-target) (yt-do "return ok?p.getVolume()/100:null"))
    "rv" (some-> (rv-target) (yt-do "return ok?p.getVolume()/100:null"))
    nil))

(defn set-volume! [x]
  (let [yt (str "if(!ok)return false;p.unMute();p.setVolume(" (* 100 x) ");return true")]
    (case @service
      "nf" (some-> (tv-target) (player-do (str "if(p){p.setVolume(" x ");return true}if(v){v.volume=" x ";return true}return false")))
      "yt" (some-> (yt-target) (yt-do yt))
      "rv" (some-> (rv-target) (yt-do yt))
      nil)))

(defn remember-volume!
  "After a Vol press: the level it left is now this person's own."
  [u]
  (when-let [v (when u (current-volume))]
    (swap! person-volume assoc u v)
    (update-state! #(assoc-in % [:volume u] v))))

(defn volume-floor-2!
  "volume-floor!, per person (2026-10-17). Whatever starts plays at the
   person's own volume, when they have one. Then, for anyone, nearly silent
   (below 5%) becomes 20%: the old floor (below 30% -> 50%) undid Rica's
   quiet 15% on every song."
  [u]
  (when-let [cur (current-volume)]
    (let [want (or (get @person-volume u) cur)
          want (if (< want 0.05) 0.2 want)]
      (when (> (abs (- cur want)) 0.02)
        (set-volume! want)
        (println "volume:" (or u "-") cur "->" want)))))

(defn volume-watch!
  "volume-floor-2! every 250ms for 10s, from the moment of a press: a newly
   loaded YouTube player starts at YouTube's own saved 5%, and the old single
   check 4s after the press left a video too quiet for those seconds
   (2026-10-23). Each check changes nothing once the volume is right, and
   reads the person's volume afresh, so a Vol press meanwhile isn't undone."
  [u]
  (future
    (let [end (+ (System/currentTimeMillis) 10000)]
      (loop []
        (try (volume-floor-2! u) (catch Exception _ nil))
        (when (< (System/currentTimeMillis) end)
          (Thread/sleep 250)
          (recur))))))

(def watch-until "press-watch! runs until this time, ms." (atom 0))

(defn press-watch!
  "For 3 minutes after a press, log each change in YouTube's player state,
   the HDMI sound output's state and the screen's power, with the time: to
   catch the TV's blue no-signal screen ~20s after a first play after a power
   cut (2026-10-24; not the idle blank, keep-awake was on). One watcher at a
   time; a new press extends it. Log lines start `watch:`."
  []
  (let [running? (> @watch-until (System/currentTimeMillis))]
    (reset! watch-until (+ (System/currentTimeMillis) (* 3 60 1000)))
    (when-not running?
      (future
        (loop [last nil]
          (let [yt   (try (some-> (when (= "yt" @service) (yt-target))
                                  (yt-do "return ok?p.getPlayerState():'no player'"))
                          (catch Exception _ "?"))
                sink (try (some->> (:out (p/shell {:out :string :err :string :continue true
                                                   :extra-env {"XDG_RUNTIME_DIR" runtime-dir}}
                                                  "pactl" "list" "short" "sinks"))
                                   str/split-lines
                                   (some #(when (str/includes? % "hdmi")
                                            (let [f (str/split % #"\t")] (str (first f) " " (last f))))))
                          (catch Exception _ "?"))
                scr  (try (some->> (:out (p/shell {:out :string :err :string :continue true
                                                   :extra-env {"DBUS_SESSION_BUS_ADDRESS" (str "unix:path=" runtime-dir "/bus")}}
                                                  "gdbus" "call" "--session" "--dest" "org.gnome.Mutter.DisplayConfig"
                                                  "--object-path" "/org/gnome/Mutter/DisplayConfig" "--method"
                                                  "org.freedesktop.DBus.Properties.Get" "org.gnome.Mutter.DisplayConfig" "PowerSaveMode"))
                                   (re-find #"\d"))
                          (catch Exception _ "?"))
                now  {:yt yt :hdmi (or sink "none") :screen-power scr}]
            (when (not= now last)
              (println "watch:" (pr-str now)))
            (when (< (System/currentTimeMillis) @watch-until)
              (Thread/sleep 500)
              (recur now))))))))

(defn handler-7
  "handler-6, plus volume-floor! after each press that starts or loads
   something to watch (a few seconds on, once the player is there)."
  [req]
  (let [starts? (#{"/act/play" "/act/yt-play" "/act/resume" "/act/switch" "/act/start"
                   "/act/profile" "/act/page-video" "/act/yt-next" "/act/yt-nope"
                   ;; a plain play: the last person's level was kept until a Vol
                   ;; press (2026-10-24); a new person (/act/who) is in handler-9
                   "/act/toggle"} (:uri req))
        ;; from the start of the press, not after it (2026-10-23)
        _   (when starts? (volume-watch! (:u (query req))))
        res (handler-6 req)]
    (when starts?
      (volume-watch! (:u (query req)))
      (press-watch!))
    (when (= "/act/volume" (:uri req))
      (future (Thread/sleep 300)
              (try (remember-volume! (:u (query req))) (catch Exception e (println "remember-volume:" (ex-message e))))))
    res))

(defn handler-8
  "handler-7, plus remembering when a phone last pressed something, so a later
   use of the TV's keyboard or mouse can be told apart (local-status)."
  [req]
  (let [res (handler-7 req)]
    (when (str/starts-with? (str (:uri req)) "/act/")
      (reset! last-press (System/currentTimeMillis)))
    res))

;; ---------------------------------------------------------------- Photos
;; Photos (2026-10-16): the phone's Photos button (only for :photos? people)
;; shows the pictures and videos under :photos-dir, one at a time, full screen
;; in a tab of their own on the TV (/photos/tv). Newest first. Archive takes
;; one out of the collection by moving it, same path, under :photos-archive:
;; nothing is ever deleted, and Undo moves the last ones back. Ours is Rica's
;; phone backup on the Synology, mounted on the TV over SMB.

(def photos-dir (some-> (:photos-dir config) io/file .getCanonicalFile))
(def photos-archive (some-> (:photos-archive config) io/file))

(def photo-exts #{"jpg" "jpeg" "png" "gif" "webp" "mp4" "mov" "webm" "3gp"})
(def video-exts #{"mp4" "mov" "webm" "3gp"})

(defn ext-of [s] (some-> (re-find #"\.([^./]+)$" (str s)) second str/lower-case))

(defn photo-time
  "When a photo was taken, ms: from its name (IMG_20260319_173238.jpg,
   2026-03-02-....mp4, or ms since 1970 as in FB_IMG_1712345678901.jpg), else
   the file's own time."
  [^java.io.File f]
  (let [n (.getName f)
        at (fn [s pat] (try (-> (java.time.LocalDateTime/parse s (java.time.format.DateTimeFormatter/ofPattern pat))
                                (.atZone (java.time.ZoneId/systemDefault)) .toInstant .toEpochMilli)
                            (catch Exception _ nil)))]
    (or (when-let [[_ d t] (re-find #"(?<!\d)(20\d{6})_?(\d{6})(?!\d)" n)] (at (str d t) "yyyyMMddHHmmss"))
        (when-let [[_ d] (re-find #"(?<!\d)(20\d\d-\d\d-\d\d)(?!\d)" n)] (at (str d " 12:00") "yyyy-MM-dd HH:mm"))
        (when-let [[_ ms] (re-find #"(?<!\d)(1[5-9]\d{11})(?!\d)" n)] (parse-long ms))
        (.lastModified f))))

(def ph-index "Every photo: [{:rel path-under-photos-dir :t ms}], newest first." (atom nil))
(def ph-at "The photo showing, by :rel." (atom nil))
(def ph-id "The photos tab's target id." (atom nil))

(defn index-photos!
  "Walk photos-dir (skipping Synology's @eaDir thumbnails and dot folders)."
  []
  (let [root (.getPath photos-dir)
        fs   (->> (file-seq photos-dir)
                  (remove #(some (fn [part] (or (str/starts-with? part "@") (str/starts-with? part ".")))
                                 (str/split (subs (.getPath ^java.io.File %) (count root)) #"/")))
                  (filter #(and (.isFile ^java.io.File %) (photo-exts (ext-of (.getName ^java.io.File %))))))]
    (reset! ph-index (vec (sort-by (comp - :t)
                                   (for [^java.io.File f fs]
                                     {:rel (subs (.getPath f) (inc (count root))) :t (photo-time f)}))))))

(defn ph-file ^java.io.File [rel]
  (let [f (.getCanonicalFile (io/file photos-dir rel))]
    (when (str/starts-with? (.getPath f) (str (.getPath photos-dir) "/")) f)))

;; Video copies the TV can play (2026-10-16): most of Rica's phone videos are
;; HEVC, which this Chrome can't show (2014 graphics, no HEVC; sound only).
;; The server makes an H.264 copy of each, same path under :videos-dir, and
;; Photos plays the copy. It works only while the TV is idle: nothing making
;; sound and no phone press or TV keyboard/mouse use for 10 minutes. The
;; moment that changes, ffmpeg is frozen (SIGSTOP), and it carries on (SIGCONT)
;; once idle again. Lowest priority too. Newest first. A video's codec is
;; found once with ffprobe and kept in video-codecs.edn.

(def videos-dir (some-> (:videos-dir config) io/file))
(def codecs-file (str base-dir "/video-codecs.edn"))
(def video-codecs "{rel codec} for each video probed." (atom (try (read-string (slurp codecs-file)) (catch Exception _ {}))))

(defn video-codec [rel]
  (or (get @video-codecs rel)
      (let [c (try (str/trim (:out (p/shell {:out :string :err :string :continue true}
                                            "ffprobe" "-v" "error" "-select_streams" "v:0"
                                            "-show_entries" "stream=codec_name" "-of" "csv=p=0"
                                            (str (io/file photos-dir rel)))))
                   (catch Exception _ ""))
            c (first (str/split c #"[,\s]"))]
        (when (seq c)
          (swap! video-codecs assoc rel c)
          (spit codecs-file (pr-str @video-codecs)))
        (not-empty c))))

(defn video-copy ^java.io.File [rel] (when videos-dir (io/file videos-dir rel)))

(defn needs-copy? [rel] (not= "h264" (video-codec rel)))

(defn playable-file
  "The file to send for rel: a video's H.264 copy when it has one."
  ^java.io.File [rel]
  (let [c (video-copy rel)]
    (if (and c (video-exts (ext-of rel)) (.isFile c)) c (ph-file rel))))

(defn unconverted? [rel]
  (and (video-exts (ext-of rel)) (needs-copy? rel)
       (not (some-> (video-copy rel) .isFile))))

(defn tv-idle?
  "Nothing making sound, and no press or TV keyboard/mouse use for 10 min."
  []
  (let [quiet (try (str/blank? (:out (p/shell {:out :string :err :string :continue true
                                               :extra-env {"XDG_RUNTIME_DIR" runtime-dir}}
                                              "pactl" "list" "short" "sink-inputs")))
                   (catch Exception _ false))
        ago   (- (System/currentTimeMillis) (max (or @last-press 0) (or @last-touch 0)))]
    (and quiet (> ago (* 10 60 1000)))))

(def converting "The ffmpeg at work: {:proc p :rel rel :stopped bool}, or nil." (atom nil))

(def convert-off-until "No converting before this, ms (state.edn :convert-off-until; /act/convert-off)." (atom 0))

(defn signal!
  "Send sig to the conversion c's ffmpeg, found by its output file (unique)."
  [c sig]
  (p/shell {:continue true} "pkill" (str "-" sig) "-f" (str (:part c))))

(def indexed-at "When the conversion last re-walked the photos, to catch new files." (atom 0))

(defn next-to-convert []
  (when (and videos-dir photos-dir (.isDirectory ^java.io.File photos-dir))
    (when (or (nil? @ph-index) (> (- (System/currentTimeMillis) @indexed-at) (* 10 60 1000)))
      (index-photos!)
      (reset! indexed-at (System/currentTimeMillis)))
    (first (filter #(and (video-exts (ext-of %)) (not (get-in @video-codecs [:failed %])) (unconverted? %))
                   (map :rel @ph-index)))))

(defn start-convert! [rel]
  (let [out  (video-copy rel)
        part (io/file (str (.getPath out) ".part.mp4"))]
    (.mkdirs (.getParentFile out))
    (println "videos: converting" rel)
    (reset! converting
            {:rel rel :part part :out out :stopped false
             :proc (p/process {:err :string :out :string}
                              "nice" "-n" "19" "ffmpeg" "-v" "error" "-y" "-threads" "1" "-i" (str (io/file photos-dir rel))
                              "-threads" "1" "-c:v" "libx264" "-preset" "veryfast" "-crf" "22" "-pix_fmt" "yuv420p"
                              "-c:a" "aac" "-b:a" "128k" "-movflags" "+faststart" (str part))})))

(defn convert-tick!
  "Every few seconds: freeze, thaw, finish or start a conversion."
  []
  (let [c @converting idle (and (> (System/currentTimeMillis) @convert-off-until) (tv-idle?))]
    (cond
      (and c (not (p/alive? (:proc c))))
      (let [r @(:proc c)]
        (reset! converting nil)
        (if (zero? (:exit r))
          (do (fs/move (:part c) (:out c) {:replace-existing true})
              (println "videos: done" (:rel c)))
          (do (fs/delete-if-exists (:part c))
              (swap! video-codecs assoc-in [:failed (:rel c)] true)
              (spit codecs-file (pr-str @video-codecs))
              (println "videos: failed" (:rel c) (str/trim (str (:err r)))))))
      (and c (not idle) (not (:stopped c))) (do (signal! c "STOP") (swap! converting assoc :stopped true))
      (and c idle (:stopped c))             (do (signal! c "CONT") (swap! converting assoc :stopped false))
      (and (nil? c) idle)                   (some-> (next-to-convert) start-convert!))))

(defn convert-videos!
  "The converter's own loop, for good. Leftover .part files from a restart
   are removed first (systemd ends ffmpeg with the server)."
  []
  (future
    (when videos-dir
      (doseq [f (file-seq videos-dir) :when (str/ends-with? (.getName ^java.io.File f) ".part.mp4")]
        (fs/delete-if-exists f))
      (loop []
        (Thread/sleep 3000)
        (try (convert-tick!) (catch Exception e (println "videos:" (ex-message e))))
        (recur)))))

(defn ph-pos [] (or (first (keep-indexed #(when (= @ph-at (:rel %2)) %1) @ph-index)) 0))

(defn ph-now
  "What the TV shows and the phone says: the photo at ph-at, and the next
   one for the TV to fetch ahead."
  []
  (let [ix @ph-index i (ph-pos) p (get ix i)]
    (if-not p
      {:total 0}
      {:rel (:rel p) :n (inc i) :total (count ix)
       :video (boolean (video-exts (ext-of (:rel p))))
       :unconverted (boolean (and (video-exts (ext-of (:rel p))) (unconverted? (:rel p))))
       :date (.format (.atZone (java.time.Instant/ofEpochMilli (long (:t p))) (java.time.ZoneId/systemDefault))
                      (java.time.format.DateTimeFormatter/ofPattern "EEE d MMM yyyy, h:mm a"))
       :next (:rel (get ix (inc i)))
       :undo (boolean (seq (:ph-archived (load-state))))})))

(defn ph-target []
  (when-let [id @ph-id] (first (filter #(= id (:id %)) (targets)))))

(defn ph-show!
  "Make the TV's photos tab show ph-now (it fetches it), and keep the place."
  []
  (update-state! #(assoc % :ph-at @ph-at))
  (when-let [t (ph-target)] (try (js t "window.showNow&&showNow()") (catch Exception _ nil))))

(defn ph-open!
  "Switch the TV to Photos: pause the players, then the photos tab in front
   (opened if it isn't there)."
  []
  (cond
    (nil? photos-dir) {:ok false :error "Photos aren't set up yet (:photos-dir)"}
    (not (.isDirectory ^java.io.File photos-dir)) {:ok false :error "Can't reach the photos (is the Synology on?)"}
    :else
    (do (pause-all)
        (when (or (nil? @ph-index) (empty? @ph-index)) (index-photos!))
        (reset! service "ph")
        (if-let [t (ph-target)]
          (cdp t "Page.bringToFront")
          (let [r (http/put (str "http://127.0.0.1:" cdp-port "/json/new?"
                                 (java.net.URLEncoder/encode (str "http://127.0.0.1:" port "/photos/tv") "UTF-8"))
                            {:throw false})
                t (json/parse-string (:body r) true)]
            (reset! ph-id (:id t))
            (update-state! #(assoc % :ph-id (:id t)))
            (http/get (str "http://127.0.0.1:" cdp-port "/json/activate/" (:id t)) {:throw false})))
        {:ok true :count (count @ph-index)})))

(defn ph-step!
  "Move by n photos (+ older, - newer), or by a month when month is true."
  [n month?]
  (let [ix @ph-index i (ph-pos)]
    (when (seq ix)
      (let [j (if month?
                (let [cal #(let [d (.atZone (java.time.Instant/ofEpochMilli (long %)) (java.time.ZoneId/systemDefault))]
                             (+ (* 12 (.getYear d)) (.getMonthValue d)))
                      m (cal (:t (ix i)))]
                  (if (pos? n)
                    (or (first (filter #(< (cal (:t (ix %))) m) (range i (count ix)))) (dec (count ix)))
                    ;; the first photo of the next newer month
                    (let [newer (filter #(> (cal (:t (ix %))) m) (range i -1 -1))]
                      (if-let [k (first newer)]
                        (last (take-while #(= (cal (:t (ix %))) (cal (:t (ix k)))) (range k -1 -1)))
                        0))))
                (max 0 (min (dec (count ix)) (+ i n))))]
        (reset! ph-at (:rel (ix j)))
        (ph-show!)))
    {:ok (boolean (seq ix))}))

(defn ph-archive!
  "Move the photo showing to photos-archive, same path below it; the next
   older one shows. Recorded in state.edn :ph-archived (last 50, newest
   first), for Undo."
  []
  (let [rel @ph-at f (some-> rel ph-file)]
    (cond
      (nil? photos-archive) {:ok false :error "No archive folder set (:photos-archive)"}
      (not (and f (.isFile f))) {:ok false :error "That photo isn't there any more"}
      :else
      (let [to (io/file photos-archive rel) i (ph-pos)]
        (.mkdirs (.getParentFile to))
        (fs/move f to)
        (println "photos: archived" rel)
        (update-state! #(update % :ph-archived (fn [a] (vec (take 50 (cons rel a))))))
        (swap! ph-index (fn [ix] (vec (remove #(= rel (:rel %)) ix))))
        (reset! ph-at (:rel (get @ph-index (min i (dec (count @ph-index))))))
        (ph-show!)
        {:ok true}))))

(defn ph-undo!
  "Move the last archived photo back, and show it."
  []
  (if-let [rel (first (:ph-archived (load-state)))]
    (let [from (io/file photos-archive rel) to (io/file photos-dir rel)]
      (if-not (.isFile from)
        (do (update-state! #(update % :ph-archived (comp vec rest)))
            {:ok false :error "It isn't in the archive any more"})
        (do (.mkdirs (.getParentFile to))
            (fs/move from to)
            (println "photos: put back" rel)
            (update-state! #(update % :ph-archived (comp vec rest)))
            (swap! ph-index (fn [ix] (vec (sort-by (comp - :t) (conj ix {:rel rel :t (photo-time to)})))))
            (reset! ph-at rel)
            (ph-show!)
            {:ok true})))
    {:ok false :error "Nothing to undo"}))

(defn ph-toggle!
  "Play or pause a video showing on the photos tab."
  []
  (if-let [t (ph-target)]
    {:ok (boolean (js t "(()=>{const v=document.querySelector('video');if(!v)return false;v.paused?v.play():v.pause();return true})()"))}
    {:ok false :error "Photos aren't showing on the TV"}))

(defn serve-file
  "f, honouring a Range request (videos need it to start and to seek)."
  [^java.io.File f req]
  (let [len  (.length f)
        type (case (ext-of (.getName f))
               ("jpg" "jpeg") "image/jpeg" "png" "image/png" "gif" "image/gif" "webp" "image/webp"
               "mp4" "video/mp4" "mov" "video/quicktime" "webm" "video/webm" "3gp" "video/3gpp"
               "application/octet-stream")
        [_ a b] (re-find #"bytes=(\d*)-(\d*)" (str (get-in req [:headers "range"])))]
    (if-not a
      {:status 200 :headers {"Content-Type" type "Accept-Ranges" "bytes" "Content-Length" (str len)} :body f}
      (let [start (if (seq a) (parse-long a) (max 0 (- len (parse-long b))))
            end   (min (dec len) (if (and (seq a) (seq b)) (parse-long b) (+ start (* 4 1024 1024) -1)))
            buf   (byte-array (inc (- end start)))]
        (with-open [r (java.io.RandomAccessFile. f "r")]
          (.seek r start) (.readFully r buf))
        {:status 206
         :headers {"Content-Type" type "Accept-Ranges" "bytes"
                   "Content-Range" (str "bytes " start "-" end "/" len)}
         :body (java.io.ByteArrayInputStream. buf)}))))

(def photos-tv-html
  "<!doctype html><html><head><meta charset=utf-8><title>Photos</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden;cursor:none}
#box{position:fixed;inset:0;display:flex;align-items:center;justify-content:center}
#box img,#box video{max-width:100vw;max-height:100vh;object-fit:contain}
#cap{position:fixed;left:24px;bottom:18px;color:#ccc;font:22px system-ui,sans-serif;text-shadow:0 0 6px #000}
#none{color:#888;font:32px system-ui,sans-serif;text-align:center}#none small{font-size:22px}</style></head><body>
<div id=box></div><div id=cap></div>
<script>
var shown=null,ahead=null;
function src(rel){return '/photos/file?p='+encodeURIComponent(rel)}
function showNow(){return fetch('/photos/now').then(r=>r.json()).then(function(n){
  var box=document.getElementById('box');
  if(!n.rel){box.innerHTML='<div id=none>No photos</div>';document.getElementById('cap').textContent='';shown=null;return true}
  var key=n.rel+(n.unconverted?'|u':'');
  if(n.build){if(window.tvrBuild&&window.tvrBuild!==n.build&&key!==shown){location.reload();return true}window.tvrBuild=n.build}
  if(key!==shown){shown=key;
    if(n.unconverted){box.innerHTML='<div id=none>This video isn’t ready yet<br><small>The TV converts videos while it’s idle</small></div>';}
    else{
    var el=document.createElement(n.video?'video':'img');el.src=src(n.rel);
    if(n.video){el.autoplay=true;el.controls=false;el.playsInline=true;if(n.volume!=null)el.volume=n.volume}
    box.replaceChildren(el)}}
  var cur=box.querySelector('img,video');if(cur){var r=n.rotate||0,side=(r%180)!==0;
    cur.style.transform=r?'rotate('+r+'deg)':'';cur.style.maxWidth=side?'100vh':'';cur.style.maxHeight=side?'100vw':''}
  document.getElementById('cap').textContent=n.date+'  ·  '+n.n+' of '+n.total;
  if(n.next&&!/\\.(mp4|mov|webm|3gp)$/i.test(n.next)){ahead=new Image();ahead.src=src(n.next)}
  return true})}
document.addEventListener('visibilitychange',function(){var v=document.querySelector('video');if(v&&document.hidden)v.pause()});
showNow();
</script></body></html>")

;; A new name on a phone (2026-10-16): the TV goes to what that person used
;; last (state.edn :last-svc), loaded with their own place, as Take over does,
;; so the phone's buttons are theirs. Nothing used yet: nothing changes.

(def shared-list "The last list: {:seq n :kind k :svc s :r reply}." (atom {:seq 0}))

(defn who!
  "Switch the TV to user's last service, with their last show, video, page
   or photo."
  [user]
  (let [svc (and user (get-in (load-state) [:last-svc user]))]
    (println "who:" user "->" (or svc "nothing yet"))
    ;; the last person's list (results, Up next, links) goes from every phone (2026-10-24)
    (swap! shared-list (fn [{n :seq}] {:seq (inc n) :kind "clear"}))
    (cond
      (nil? svc)  {:ok true}
      (= "ph" svc) (ph-open!)
      :else        (switch-service-2! svc user))))

(defn handler-9
  "handler-8, plus Photos: the TV's photos page and its files, and the phone's
   ph- presses; /status says \"ph\" while Photos is the current service."
  [req]
  (let [uri (:uri req) q (query req)
        ph  (fn [r] (reset! last-press (System/currentTimeMillis)) (reply r))]
    (case uri
      "/photos/tv"   {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body photos-tv-html}
      "/photos/now"  (reply (ph-now))
      "/photos/file" (if-let [f (and photos-dir (ph-file (:p q)) (playable-file (:p q)))]
                       (if (.isFile f) (serve-file f req) {:status 404 :body "not found"})
                       {:status 404 :body "not found"})
      "/act/ph-open"    (ph (let [r (ph-open!)]
                              (when (and (:ok r) (user-of q))
                                (update-state! #(assoc-in % [:last-svc (user-of q)] "ph")))
                              r))
      ;; and their own volume, at once (2026-10-24)
      "/act/who"        (ph (let [r (who! (user-of q))] (volume-watch! (user-of q)) r))
      "/act/ph-step"    (ph (ph-step! (or (parse-long (str (:n q))) 1) (= "1" (:month q))))
      "/act/ph-archive" (ph (ph-archive!))
      "/act/ph-undo"    (ph (ph-undo!))
      "/act/ph-toggle"  (ph (ph-toggle!))
      (let [res (handler-8 req)]
        (if (and (= "/status" uri) (= "ph" @service) (= 200 (:status res)))
          (let [s (json/parse-string (:body res) true)]
            (if (:chrome s)
              (reply (-> s (dissoc :playing :time :duration :title :url :episode :show :away :profiles)
                         (assoc :service "ph" :photo (ph-now))))
              res))
          res)))))

;; One person for the TV (2026-10-16): there's one TV, so every phone shows
;; the same person, the TV's: whoever last pressed anything or picked a name
;; on any phone (state.edn :tv-who). /status carries it as :who and each
;; phone takes it on, with that person's buttons and Recent.

(def tv-who "The TV's current person (a phone's u=)." (atom nil))

(defn handler-10
  "handler-9, plus the TV's one current person: set by each press, given out
   with /status."
  [req]
  (let [uri (:uri req)
        u   (when (str/starts-with? uri "/act/") (user-of (query req)))]
    (when (and u (not= u @tv-who))
      (reset! tv-who u)
      (update-state! #(assoc % :tv-who u)))
    (let [res (handler-9 req)]
      (if (and (= "/status" uri) @tv-who (= 200 (:status res)))
        (reply (assoc (json/parse-string (:body res) true) :who @tv-who))
        res))))

;; Shared lists (2026-10-16): what one phone looks up shows on every phone.
;; The last list asked for (search or browse results, a page's videos or
;; links, the TV's tabs) is kept here with a number; /status gives out the
;; number, and a phone that hasn't drawn that one fetches /list.


(def list-kinds
  {"browse" "results" "shows" "results" "movies" "results" "genre" "results" "search" "results"
   "yt-home" "results" "yt-subs" "results" "yt-history" "results" "yt-search" "results" "yt-upnext" "results" "yt-later" "results"
   "page-videos" "videos" "page-links" "links" "tabs" "tabs"})

(defn handler-11
  "handler-10, plus shared lists."
  [req]
  (let [uri    (:uri req)
        action (when (str/starts-with? uri "/act/") (subs uri 5))
        kind   (get list-kinds action)]
    (cond
      (= "/list" uri) (reply @shared-list)
      kind (let [res (handler-10 req)
                 r   (try (json/parse-string (:body res) true) (catch Exception _ nil))]
             (if (:ok r)
               (let [l (swap! shared-list (fn [{n :seq}]
                                            {:seq (inc n) :kind kind :r r
                                             :svc (cond (str/starts-with? action "yt-") "yt"
                                                        (= "results" kind) "nf"
                                                        (= "tabs" kind) nil
                                                        :else "page")}))]
                 (reply (assoc r :list-seq (:seq l))))
               res))
      :else (let [res (handler-10 req)]
              (if (and (= "/status" uri) (= 200 (:status res)))
                (reply (assoc (json/parse-string (:body res) true) :list-seq (:seq @shared-list)))
                res)))))

(defn handler-12
  "handler-11, but a press counts as using the TV before it runs, not after.
   A slow press (Start Chrome while a video converts) used to finish first, so
   the conversion kept every core and the press timed out."
  [req]
  (when (str/starts-with? (str (:uri req)) "/act/")
    (reset! last-press (System/currentTimeMillis))
    (when-let [c @converting] (signal! c "STOP") (swap! converting assoc :stopped true)))
  (cond
    (= "/act/nf-dialog" (:uri req))
    (reply (nf-dialog-press! (or (parse-long (or (:b (query req)) "")) 0)))
    (= "/act/convert-off" (:uri req))
    (let [h     (or (parse-double (or (:hours (query req)) "")) 10)
          until (+ (System/currentTimeMillis) (long (* h 3600 1000)))]
      (reset! convert-off-until until)
      (update-state! #(assoc % :convert-off-until until))
      (println "videos: off until" (str (java.time.Instant/ofEpochMilli until)))
      (reply {:ok true :until until}))
    :else (handler-11 req)))

(defn nocursor-js [on]
  (str "(()=>{let s=document.getElementById('tvr-nocursor');"
       (if on
         "if(!s){s=document.createElement('style');s.id='tvr-nocursor';s.textContent='*{cursor:none!important}';document.documentElement.appendChild(s)}return true"
         "if(s)s.remove();return false")
       "})()"))

(defn hide-cursor!
  "While the keyboard and mouse are locked, no cursor over Netflix or YouTube
   (2026-10-17: left over the picture after a sign-in, it showed all evening;
   Netflix hides it only after seeing the mouse move and stop). Unlocked, the
   pages get their normal cursor back."
  []
  (let [on (locked?)]
    (doseq [t (filter #(and (= "page" (:type %))
                            (or (on-site? "netflix.com" %) (on-site? "youtube.com" %)))
                      (targets))]
      (try (js t (nocursor-js on)) (catch Exception _ nil)))))

(defn nocursor-js-2
  "As nocursor-js, but says \"added\" when this call put the style in."
  [on]
  (str "(()=>{let s=document.getElementById('tvr-nocursor');"
       (if on
         "if(s)return true;s=document.createElement('style');s.id='tvr-nocursor';s.textContent='*{cursor:none!important}';document.documentElement.appendChild(s);return 'added'"
         "if(s)s.remove();return false")
       "})()"))

(defn hide-cursor-2!
  "As hide-cursor!, plus one made-up mouse move when the style goes in.
   2026-10-19: the style was on the Netflix page but the cursor still showed
   all evening. Chrome redraws the cursor only on a mouse move, and the lock
   stops real ones. Netflix shows its controls for a moment, then hides them."
  []
  (let [on (locked?)]
    (doseq [t (filter #(and (= "page" (:type %))
                            (or (on-site? "netflix.com" %) (on-site? "youtube.com" %)))
                      (targets))]
      (try (when (= "added" (js t (nocursor-js-2 on)))
             (cdp t "Input.dispatchMouseEvent" {:type "mouseMoved" :x 5 :y 5}))
           (catch Exception _ nil)))))

(defn frozen-tabs
  "The Netflix and YouTube tabs that don't answer a simple question in 4s.
   Only those two sites: script runs on no other page."
  []
  (let [ts (filter #(or (on-site? "netflix.com" %) (on-site? "youtube.com" %)) (targets))
        fs (mapv (fn [t] [t (future (try (js t "1") (catch Exception _ :error)))]) ts)]
    (for [[t f] fs :when (= ::hung (deref f 4000 ::hung))] t)))

(defn handler-13
  "Start, with Chrome already running, also closes a frozen Netflix or
   YouTube tab (2026-10-19: a frozen YouTube tab held every press up, and
   Start said only \"already running\"). The phone's last service then
   opens a fresh one, as after any Start."
  [req]
  (when (and (= "/act/start" (:uri req)) (chrome-up?))
    (doseq [t (frozen-tabs)]
      (println "start: closing frozen tab" (:url t))
      (close-tab (:id t))))
  (handler-12 req))

(def playing-js "(()=>{const v=document.querySelector('video');return !!(v&&!v.paused)})()")

(defn photos-guard!
  "While Photos is on the TV, Netflix and YouTube stay paused. 2026-10-19:
   YouTube played behind Photos - a person's video was still loading when
   Photos paused everything, and it started after."
  []
  (when (= "ph" @service)
    (doseq [t (filter #(or (on-site? "netflix.com" %) (on-site? "youtube.com" %)) (targets))]
      (when (js t playing-js)
        (println "photos: paused" (:url t) "playing behind Photos")
        (if (on-site? "netflix.com" t) (nf-pause) (yt-pause t))))))

(def unlogged-acts
  "Presses handled above handler-2, so it never logged them."
  #{"/act/who" "/act/ph-open" "/act/ph-step" "/act/ph-toggle" "/act/ph-undo"
    "/act/ph-archive" "/act/nf-dialog" "/act/convert-off"})

(defn handler-14
  "handler-13, plus the press log line (as handler-2 writes it) for the
   presses that never reached handler-2: choosing a person, Photos, Netflix
   pop-ups. 2026-10-19: Rica's Photos press was missing from the log."
  [req]
  (if-not (unlogged-acts (:uri req))
    (handler-13 req)
    (let [t0   (System/currentTimeMillis)
          q    (query req)
          res  (handler-13 req)
          ms   (- (System/currentTimeMillis) t0)
          out  (try (json/parse-string (:body res) true) (catch Exception _ nil))
          args (for [[k v] (dissoc q :u)] (str (name k) "=" (subs v 0 (min 80 (count v)))))]
      (println (str/join " " (concat [(or (:u q) "-") (str "@" (:remote-addr req)) (subs (:uri req) 5)] args
                                     [(str ms "ms") (if (:ok out) "ok" (str "FAILED: " (:error out)))])))
      (flush)
      res)))

(defn ph-video-js
  "Script for the video on the photos tab: body runs with v, the video.
   Returns false when no video is showing."
  [body]
  (str "(()=>{const v=document.querySelector('video');if(!v)return false;" body "})()"))

(defn ph-video!
  "A video control press on Photos (2026-10-19: Photos had no volume, and
   its videos often have sound). Volume is the person's own, as for Netflix
   and YouTube: what Vol leaves is remembered, and new videos start at it."
  [action q]
  (let [t (ph-target)
        u (user-of q)
        r (when t
            (js t (ph-video-js
                   (case action
                     "ph-seek"   (str "v.currentTime=Math.max(0,v.currentTime+(" (or (parse-long (or (:ms q) "")) 0) ")/1000);return true")
                     "ph-start"  "v.currentTime=0;v.play();return true"
                     "ph-mute"   "v.muted=!v.muted;return true"
                     "ph-volume" (str "v.muted=false;v.volume=Math.max(0,Math.min(1,v.volume+(" (or (parse-double (or (:d q) "")) 0) ")));return v.volume")))))]
    (when (and u (number? r))
      (swap! person-volume assoc u r)
      (update-state! #(assoc-in % [:volume u] r)))
    (cond
      (nil? t) {:ok false :error "Photos aren't showing on the TV"}
      (false? r) {:ok false :error "No video is showing"}
      :else {:ok true})))

(def ph-video-acts #{"/act/ph-seek" "/act/ph-start" "/act/ph-mute" "/act/ph-volume" "/act/ph-rotate"})

(def server-build
  "Changes on every server start. The Photos page reloads itself when it
   changes, on its next photo, so it never keeps old code after a deploy."
  (str (System/currentTimeMillis)))

(def ph-files-file
  "Settings for single photos and videos, by :rel: {rel {:rotate 90}}.
   Its own file, not state.edn, so it reads and edits easily by hand."
  (str base-dir "/photos.edn"))

(def ph-files
  (atom (try (read-string (slurp ph-files-file)) (catch Exception _ {}))))

(defn ph-rotate!
  "Turn what Photos shows a quarter turn clockwise, and remember it for that
   file (2026-10-19)."
  []
  (if-let [rel @ph-at]
    (let [r (mod (+ 90 (get-in @ph-files [rel :rotate] 0)) 360)]
      (swap! ph-files (fn [m] (if (zero? r) (update m rel dissoc :rotate) (assoc-in m [rel :rotate] r))))
      (swap! ph-files (fn [m] (if (empty? (get m rel)) (dissoc m rel) m)))
      (spit ph-files-file (str "{" (str/join "\n " (for [[k v] (sort-by key @ph-files)] (str (pr-str k) " " (pr-str v)))) "}\n"))
      (ph-show!)
      {:ok true :rotate r})
    {:ok false :error "Photos aren't showing on the TV"}))

(defn handler-15
  "handler-14, plus the video buttons on Photos (logged and stopping a
   conversion like any press), and /photos/now telling the TV page the
   person's volume for the next video."
  [req]
  (let [uri (:uri req)]
    (cond
      (ph-video-acts uri)
      (let [t0 (System/currentTimeMillis)
            q  (query req)]
        (reset! last-press t0)
        (when-let [c @converting] (signal! c "STOP") (swap! converting assoc :stopped true))
        (let [out (try (if (= uri "/act/ph-rotate") (ph-rotate!) (ph-video! (subs uri 5) q)) (catch Exception e {:ok false :error (ex-message e)}))
              args (for [[k v] (dissoc q :u)] (str (name k) "=" v))]
          (println (str/join " " (concat [(or (:u q) "-") (str "@" (:remote-addr req)) (subs uri 5)] args
                                         [(str (- (System/currentTimeMillis) t0) "ms") (if (:ok out) "ok" (str "FAILED: " (:error out)))])))
          (flush)
          (reply out)))
      (= "/photos/now" uri)
      (reply (let [_ (when (and (empty? @ph-index) photos-dir (.isDirectory ^java.io.File photos-dir))
                       ;; 2026-10-19: after a restart the list was empty until
                       ;; someone pressed Photos, so the TV said "No photos"
                       (index-photos!))
                   n (ph-now) v (get @person-volume @tv-who)]
               (cond-> (assoc n :build server-build) v (assoc :volume v)
                 (get-in @ph-files [(:rel n) :rotate]) (assoc :rotate (get-in @ph-files [(:rel n) :rotate])))))
      :else (handler-14 req))))

;; ---------------------------------------------------------------- overview
;; GNOME opens in the Activities overview after login, so after a power cut
;; (the machine starts by itself on AC) the TV showed a row of app icons until
;; someone pressed the Windows key (2026-10-24). The tick closes it, unless the
;; TV computer's own keyboard or mouse was used in the last 2 minutes.

(defn gnome-shell-prop [& args]
  (try (:out (apply p/shell {:out :string :err :string :continue true
                             :extra-env {"XDG_RUNTIME_DIR" runtime-dir
                                         "DBUS_SESSION_BUS_ADDRESS" (str "unix:path=" runtime-dir "/bus")}}
                    "gdbus" "call" "--session" "--dest" "org.gnome.Shell" "--object-path" "/org/gnome/Shell"
                    "--method" args))
       (catch Exception _ nil)))

(defn overview-tick! []
  (when (and (some-> (gnome-shell-prop "org.freedesktop.DBus.Properties.Get" "org.gnome.Shell" "OverviewActive")
                     (str/includes? "true"))
             (not (some-> @last-touch (> (- (System/currentTimeMillis) (* 2 60 1000))))))
    (gnome-shell-prop "org.freedesktop.DBus.Properties.Set" "org.gnome.Shell" "OverviewActive" "<false>")
    (println "overview: closed it")))

;; ---------------------------------------------------------------- HDMI drops
;; The HDMI link to the TV drops for a few seconds (blue screen), often ~15s
;; after play following a break. Its sound output vanishes and comes back with
;; a new id, and YouTube/Netflix pause because their sound has nowhere to go.
;; The tick watches the HDMI sink; when it went away or changed while a video
;; was playing, and no phone pressed anything since, it presses play once the
;; sink is back (2026-10-22).

(defn hdmi-sink-id
  "The HDMI sound output's id, or nil while there is none."
  []
  (try (some->> (:out (p/shell {:out :string :err :string :continue true
                                :extra-env {"XDG_RUNTIME_DIR" runtime-dir}}
                               "pactl" "list" "short" "sinks"))
                str/split-lines
                (some #(when (str/includes? % "hdmi") (first (str/split % #"\t")))))
       (catch Exception _ nil)))

(defn current-player
  "The tab of the video service that's current, and how to ask/play it."
  []
  (case @service
    "yt" (when-let [t (yt-target)] {:t t :yt true})
    "rv" (when-let [t (rv-target)] {:t t :yt true})
    "nf" (when-let [t (first (filter (partial on-site? "netflix.com") (targets)))] {:t t})
    nil))

(defn player-playing? [{:keys [t yt]}]
  (boolean (if yt (yt-do t "return ok&&[1,3].includes(p.getPlayerState())")
               (player-do t "return !!(p&&!p.isPaused())"))))

(defn player-play! [{:keys [t yt]}]
  (if yt (yt-do t "if(ok)p.playVideo();return ok")
      (player-do t "if(p&&p.isPaused())p.play();return !!p")))

(def hdmi "{:id last sink id, :playing what the last tick saw, :armed {:at ms}}" (atom {}))

(defn hdmi-tick! []
  (let [id (hdmi-sink-id)
        {:keys [playing armed] old :id} @hdmi
        drop? (or (nil? id) (and old (not= id old)))]
    (cond
      ;; it went (or came back as a new one) while a video was playing
      (and drop? playing (not armed))
      (do (println "hdmi: sound output" (if id (str "changed " old " -> " id) "gone") "while playing")
          (swap! hdmi assoc :id id :armed {:at (System/currentTimeMillis)} :playing false)
          ;; back already: resume on the next tick
          )
      (and armed id)
      (let [pl (current-player)]
        (if (and pl (or (nil? @last-press) (< @last-press (:at armed))))
          (when-not (player-playing? pl)
            (Thread/sleep 1500)
            (player-play! pl)
            (println "hdmi: back, pressed play"))
          (println "hdmi: back, a phone pressed since, left alone"))
        (reset! hdmi {:id id :armed nil :playing (boolean (some-> pl player-playing?))}))
      :else
      (reset! hdmi {:id id :armed armed
                    :playing (boolean (when id (some-> (current-player) player-playing?)))}))))

;; ---------------------------------------------------------------- keep awake
;; The real cause of the "HDMI drops" (2026-10-22): GNOME blanks the screen
;; after 15 idle minutes, and phone presses aren't user input, so it blanked
;; mid-show (drops 15.2 min after the last press) and, once a press had woken
;; it, blanked again 15s later. A blank turns HDMI off: blue screen, the sound
;; output goes, the video pauses. Now GNOME is told the session isn't idle
;; (gnome-session-inhibit, held while it runs) while a video plays or within
;; 15 min of a press; otherwise it blanks as before.

(defn awake-tick! []
  (keep-awake! (or (some-> @last-press (> (- (System/currentTimeMillis) (* 15 60 1000))))
                   (boolean (some-> (current-player) player-playing?)))))

(defn -main []
  (server/run-server handler-15 {:port port :ip "0.0.0.0" :thread 8})
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
  (reset! ph-id (:ph-id (load-state)))
  (reset! ph-at (:ph-at (load-state)))
  (reset! tv-who (:tv-who (load-state)))
  (reset! convert-off-until (or (:convert-off-until (load-state)) 0))
  (reset! person-volume (or (:volume (load-state)) {}))
  (when (and input-lock? (:input-locked (load-state))) (lock-input!))
  (when input-lock? (watch-input!))
  (convert-videos!)
  (try (adopt-front-tab!) (catch Exception e (println "adopt-front-tab:" (ex-message e))))
  ;; no current person yet: the owner of what's showing
  (when-not @tv-who (reset! tv-who (get @owner (get {"rv" "page" "rl" "page"} @service @service))))
  ;; The one tick (2026-10-15): every 3s close YouTube's chat replay and deal
  ;; with Netflix's Still watching?, and every 5th tick (15s) save everyone's
  ;; place. One loop, not one per job.
  (future (loop [n 1]
            (Thread/sleep 3000)
            ;; Chrome or not: after a power cut it may not be started yet
            (try (overview-tick!) (catch Exception e (println "overview:" (ex-message e))))
            (when (chrome-up?)
              (try (hide-yt-chat!) (catch Exception _ nil))
              (try (hide-cursor-2!) (catch Exception _ nil))
              (try (photos-guard!) (catch Exception e (println "photos-guard:" (ex-message e))))
              (try (nf-dialog-tick!) (catch Exception e (println "nf-dialog:" (ex-message e))))
              (try (nf-still-watching!) (catch Exception e (println "nf-still-watching:" (ex-message e))))
              (try (hdmi-tick!) (catch Exception e (println "hdmi:" (ex-message e))))
              (try (awake-tick!) (catch Exception e (println "keep-awake:" (ex-message e))))
              (when (zero? (mod n 5))
                (try (record-watching-2!)
                     (catch Exception e (println "record-watching:" (ex-message e))))))
            (recur (inc n))))
  @(promise))

(when (= *file* (System/getProperty "babashka.file")) (-main))
