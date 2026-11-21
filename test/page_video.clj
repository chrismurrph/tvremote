#!/usr/bin/env bb
;; A page's own video, played in place from Reading (2026-11-21): the 🎬 Videos
;; list, which tab the transport buttons act on, status, pausing, and the place
;; kept per person and page. Loads a COPY of tvremote.clj like cursor.clj, then
;; stubs targets / js / cdp, so no Chrome is touched. Run: bb test/page_video.clj
(require '[babashka.fs :as fs] '[babashka.process] '[clojure.test :as t :refer [deftest is testing]])
(let [src (str (fs/parent (fs/parent (fs/absolutize *file*))) "/tvremote.clj")
      dir (str (fs/create-temp-dir {:prefix "tvremote-pv"}))]
  (spit (str dir "/config.edn") "{:cdp-port 9333}")
  (fs/copy src (str dir "/tvremote.clj"))
  (load-file (str dir "/tvremote.clj"))
  (fs/delete-tree dir))
(in-ns 'tvremote)
(require (quote [clojure.test :as t :refer [deftest is testing]]) (quote [clojure.string :as str]))

(def pg {:id "pg" :type "page" :url "https://example.substack.com/p/a-video" :title "A video"})
(def ytab {:id "yt" :type "page" :url "https://www.youtube.com/watch?v=x"})
(def rvtab {:id "rv" :type "page" :url "https://www.youtube.com/watch?v=y"})

(defmacro quiet
  "Runs body with the TV-side effects of act stubbed and the reading tab pg."
  [& body]
  `(with-redefs [wake-screen! (constantly nil) keep-awake! (constantly nil)
                 targets (constantly [pg ytab rvtab]) page-id (atom "pg") rv-id (atom "rv")
                 yt-id (atom "yt") load-state (constantly {}) save-page-id! (constantly nil)
                 update-state! (constantly nil)]
     ~@body))

(deftest transport-routes
  (quiet
   (testing "YouTube and the reading-video tab go to yt-act, Reading refuses"
     (with-redefs [yt-act (fn [t a _] {:ok true :tab (:id t) :a a})]
       (with-redefs [service (atom "yt")] (is (= {:ok true :tab "yt" :a "toggle"} (act "toggle" {}))))
       (with-redefs [service (atom "rv")] (is (= {:ok true :tab "rv" :a "seek"} (act "seek" {:ms "10000"}))))
       (with-redefs [service (atom "page")] (is (false? (:ok (act "toggle" {})))))))))

(deftest page-video-action
  (quiet
   (testing "a YouTube id from the list plays in the reading-video tab"
     (with-redefs [rv-play (fn [id] [:rv id])]
       (is (= {:ok [:rv "abcdefghijk"]} (act "page-video" {:id "abcdefghijk"})))))))

(deftest page-videos-list
  (quiet
   (testing "the list is what page-videos-js finds"
     (with-redefs [js (fn [_ e] (when (= e page-videos-js) [{:id "abcdefghijk" :title "T"}]))]
       (is (= {:ok true :videos [{:id "abcdefghijk" :title "T"}]} (act "page-videos" {})))))))

(deftest status-reading
  (quiet
   (testing "Reading: just the service and the page"
     (with-redefs [service (atom "page")]
       (is (= {:chrome true :service "page" :page {:title "A video" :url (:url pg)}} (status)))))))

(deftest pause-all-pauses
  (testing "a switch pauses YouTube and Netflix"
    (let [got (atom [])]
      (with-redefs [pause-youtube! #(swap! got conj :yt) nf-pause #(swap! got conj :nf)]
        (pause-all))
      (is (= [:yt :nf] (take 2 @got))))))

(deftest recent-services
  (with-redefs [shown-history (fn [_ k] [{:title (name k) :key "k" :url "https://h.example/x"}])]
    (testing "Reading, a link and a reading video all show the page's Recent"
      (is (= "page" (:title (first (recent "chris" "page")))))
      (is (= "page" (:title (first (recent "chris" "rv"))))))))

(deftest page-own-video
  (quiet
   (testing "the transport buttons act on the page's own video while pv is current"
     (with-redefs [service (atom "pv") pv-act (fn [t a _] {:ok true :tab (:id t) :a a})]
       (is (= {:ok true :tab "pg" :a "toggle"} (act "toggle" {})))
       (is (= {:ok true :tab "pg" :a "volume"} (act "volume" {:d "0.1"})))))
   (testing "pv with no reading tab: refused"
     (with-redefs [service (atom "pv") page-id (atom nil) targets (constantly [ytab])]
       (is (false? (:ok (act "toggle" {}))))))
   (testing "\"here\" from the list plays the page's own video, for the phone's person"
     (with-redefs [pv-play! (fn [u] {:ok true :who u})]
       (is (= {:ok true :who "chris"} (act "page-video" {:id "here" :u "chris"})))))
   (testing "the list puts the page's own video first"
     (with-redefs [js (fn [_ e] (cond (= e page-media-js) {:id "here" :title "This page's video: A video"}
                                      (= e page-videos-js) [{:id "abcdefghijk" :title "T"}]))]
       (is (= ["here" "abcdefghijk"] (map :id (:videos (act "page-videos" {})))))))
   (testing "no video of its own: the list is as before"
     (with-redefs [js (fn [_ e] (when (= e page-videos-js) [{:id "abcdefghijk" :title "T"}]))]
       (is (= ["abcdefghijk"] (map :id (:videos (act "page-videos" {})))))))))

(deftest pv-play-from-place
  (quiet
   (let [got (atom {})]
     (with-redefs [nf-pause (constantly nil) pause-youtube! (constantly nil) cdp (constantly nil)
                   service (atom "page") pv-owner (atom nil)
                   load-state (constantly {:video-place {"chris" {(:url pg) {:time-s 1234}}}})
                   js (fn [_ e] (swap! got assoc :js e) true)]
       (testing "starts from the person's place, makes pv current, remembers who"
         (is (= {:ok true} (pv-play! "chris")))
         (is (str/includes? (:js @got) "const at=1234;"))
         (is (= "pv" @service))
         (is (= "chris" @pv-owner)))
       (testing "someone with no place: from the start"
         (pv-play! "rica")
         (is (not (str/includes? (:js @got) "const at="))))))))

(deftest status-page-video
  (quiet
   (testing "pv: the video's state, with the page"
     (with-redefs [service (atom "pv") js (constantly {:url (:url pg) :playing true :time 5000.0 :duration 60000.0})]
       (let [s (status)]
         (is (= "pv" (:service s)))
         (is (true? (:playing s)))
         (is (= "A video" (get-in s [:page :title]))))))
   (testing "a page's own video shows the page's Recent"
     (with-redefs [shown-history (fn [_ k] [{:title (name k) :key "k"}])]
       (is (= "page" (:title (first (recent "chris" "pv")))))))))

(deftest pv-pausing
  (quiet
   (let [ran (atom 0)]
     (with-redefs [js (fn [_ e] (when (str/includes? e "v.pause()") (swap! ran inc)) true)]
       (testing "the tick pauses it when pv is not current"
         (with-redefs [service (atom "yt")] (pv-guard!))
         (is (= 1 @ran)))
       (testing "and leaves it alone while pv is current"
         (with-redefs [service (atom "pv")] (pv-guard!))
         (is (= 1 @ran)))
       (testing "a switch (pause-all) pauses it too"
         (with-redefs [pause-youtube! (constantly nil) nf-pause (constantly nil)] (pause-all))
         (is (= 2 @ran)))))))

(deftest pv-place-saved
  (quiet
   (let [st (atom {})]
     (with-redefs [update-state! (fn [f] (swap! st f)) last-saved-2 (atom {}) pv-owner (atom "chris")]
       (with-redefs [service (atom "pv") js (constantly {:url (:url pg) :time 100000.0 :duration 3600000.0})]
         (testing "saved for whoever started it, per page"
           (record-page-video!)
           (is (= {:time-s 100 :dur-s 3600} (get-in @st [:video-place "chris" (:url pg)])))))
       (with-redefs [service (atom "pv") js (constantly {:url (:url pg) :time 105000.0 :duration 3600000.0})]
         (testing "not again until it moved 15s"
           (record-page-video!)
           (is (= 100 (get-in @st [:video-place "chris" (:url pg) :time-s])))))
       (with-redefs [service (atom "page") js (constantly {:url (:url pg) :time 900000.0 :duration 3600000.0})]
         (testing "not while pv is not current"
           (record-page-video!)
           (is (= 100 (get-in @st [:video-place "chris" (:url pg) :time-s])))))))))

(let [{:keys [fail error test pass]} (t/run-tests 'tvremote)]
  (println (str test " tests, " (+ pass fail error) " checks, " (+ fail error) " failing"))
  (System/exit (if (pos? (+ fail error)) 1 0)))
