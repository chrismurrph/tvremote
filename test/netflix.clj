#!/usr/bin/env bb
;; Netflix status and the play/pause press (2026-11-26). The scripts the server
;; sends to the Netflix tab run here in node, against a fake Netflix player, so
;; what they really do is checked, not just their text. Loads a COPY of
;; tvremote.clj like page_video.clj, so no Chrome is touched.
;; Run: bb test/netflix.clj
(require '[babashka.fs :as fs] '[babashka.process :as proc] '[cheshire.core :as json]
         '[clojure.test :as t :refer [deftest is testing]])
(let [src (str (fs/parent (fs/parent (fs/absolutize *file*))) "/tvremote.clj")
      dir (str (fs/create-temp-dir {:prefix "tvremote-nf"}))]
  (spit (str dir "/config.edn") "{:cdp-port 9333}")
  (fs/copy src (str dir "/tvremote.clj"))
  (load-file (str dir "/tvremote.clj"))
  (fs/delete-tree dir))
(in-ns 'tvremote)
(require '[clojure.test :as t :refer [deftest is testing]] '[babashka.process :as proc]
         '[cheshire.core :as json])

(def nf {:id "nf" :type "page" :url "https://www.netflix.com/watch/80177770"})

(defn run-nf
  "Runs expr in node against a fake Netflix player in state st ({:paused :err}),
   the way js runs it in the tab. Returns {:r value :played n :paused n}."
  [st expr]
  (let [prelude (str "const st=" (json/generate-string st) ";
const P={played:0,pausedN:0,isPaused(){return !!st.paused},play(){this.played++;st.paused=false},
  pause(){this.pausedN++;st.paused=true},getCurrentTime(){return 6186886},getDuration(){return 8086453},
  getVolume(){return 1},getMovieId(){return 80177770},getError(){return st.err||null},seek(){}};
globalThis.netflix={appContext:{state:{playerApp:{getAPI:()=>({videoPlayer:{
  getAllPlayerSessionIds:()=>['watch-1'],getVideoPlayerBySessionId:()=>P}}),
  getState:()=>({videoPlayer:{videoMetadata:{}}})}}},falcorCache:{videos:{}}};
globalThis.location={href:'https://www.netflix.com/watch/80177770'};
globalThis.document={querySelector:()=>null};
")
        script (str prelude "Promise.resolve(" expr ").then(r=>console.log(JSON.stringify({r:r===undefined?null:r,played:P.played,paused:P.pausedN})))")
        out (:out (proc/shell {:out :string :in script} "node"))]
    (json/parse-string out true)))

(deftest status-plays
  (testing "a playing film reads as playing"
    (is (true? (get-in (run-nf {:paused false} (str "(()=>{const p=" player-js ";const v=document.querySelector('video');" status-js "})()"))
                       [:r :playing]))))
  (testing "a paused film reads as paused"
    (is (false? (get-in (run-nf {:paused true} (str "(()=>{const p=" player-js ";const v=document.querySelector('video');" status-js "})()"))
                        [:r :playing])))))

(deftest toggle-press
  (testing "toggle on a paused film plays it"
    (let [r (run-nf {:paused true} (str "(()=>{const p=" player-js ";const v=document.querySelector('video');"
                                        "if(p){p.isPaused()?p.play():p.pause();return true}
                if(v){v.paused?v.play():v.pause();return true}return false" "})()"))]
      (is (= 1 (:played r)))))
  (testing "the press goes to the Netflix tab"
    (let [sent (atom [])]
      (with-redefs [wake-screen! (constantly nil) keep-awake! (constantly nil) chrome-up? (constantly true)
                    nf-mark (constantly nil) service (atom "nf") tv-target (constantly nf)
                    js (fn [t expr] (swap! sent conj (:id t)) (:r (run-nf {:paused true} expr)))]
        (is (= {:ok true} (act "toggle" {})))
        (is (= ["nf"] (distinct @sent)))))))

;; 2026-11-26: Netflix's M7500 ("pause_timeout") after a film sat paused too long
(def m7500 {:display {:code "M7500"} :messageIdList ["pause_timeout" "M7500"]})

(deftest status-stuck
  (testing "a player stuck on an error is not playing, and says which error"
    (let [r (:r (run-nf {:paused false :err m7500} (str "(()=>{const p=" player-js ";const v=document.querySelector('video');" status-js "})()")))]
      (is (false? (:playing r)))
      (is (= "M7500" (:nf-error r)))))
  (testing "no error, no nf-error"
    (is (not (contains? (:r (run-nf {:paused false} (str "(()=>{const p=" player-js ";const v=document.querySelector('video');" status-js "})()")))
                        :nf-error))))
  (testing "nf-error reads the code"
    (with-redefs [js (fn [_ expr] (:r (run-nf {:err m7500} expr)))]
      (is (= "M7500" (nf-error nf))))
    (with-redefs [js (fn [_ expr] (:r (run-nf {} expr)))]
      (is (nil? (nf-error nf))))))

(deftest toggle-stuck
  (testing "⏯ on a stuck player reloads the tab, waits for it, then plays"
    (let [st (atom {:paused true :err m7500}) cdps (atom [])]
      (with-redefs [wake-screen! (constantly nil) keep-awake! (constantly nil) chrome-up? (constantly true)
                    nf-mark (constantly nil) service (atom "nf") tv-target (constantly nf)
                    nf-recovered (atom 0)
                    cdp (fn [_ m _] (swap! cdps conj m) (when (= m "Page.reload") (swap! st dissoc :err)) {})
                    js (fn [_ expr] (let [r (run-nf @st expr)]
                                      (when (pos? (:played r)) (swap! st assoc :paused false))
                                      (:r r)))]
        (is (= {:ok true} (act "toggle" {})))
        (is (= ["Page.reload"] @cdps))
        (is (false? (:paused @st))))))
  (testing "a second press within 20s does not reload again"
    (let [cdps (atom [])]
      (with-redefs [nf-recovered (atom (System/currentTimeMillis))
                    cdp (fn [_ m _] (swap! cdps conj m) {})]
        (is (nil? (nf-recover! nf)))
        (is (empty? @cdps)))))
  (testing "seek on a stuck player reloads first, then seeks"
    (let [st (atom {:paused false :err m7500}) order (atom [])]
      (with-redefs [wake-screen! (constantly nil) keep-awake! (constantly nil) chrome-up? (constantly true)
                    nf-mark (constantly nil) service (atom "nf") tv-target (constantly nf)
                    nf-recovered (atom 0)
                    cdp (fn [_ m _] (swap! order conj m) (when (= m "Page.reload") (swap! st dissoc :err)) {})
                    js (fn [_ expr] (:r (run-nf @st expr)))
                    seek (fn [_ ms] (swap! order conj [:seek ms]) true)]
        (is (= {:ok true} (act "seek" {:ms "-10000"})))
        (is (= ["Page.reload" [:seek -10000]] @order))))))

(let [{:keys [fail error test pass]} (t/run-tests 'tvremote)]
  (println (str test " tests, " (+ pass fail error) " checks, " (+ fail error) " failing"))
  (System/exit (if (pos? (+ fail error)) 1 0)))
