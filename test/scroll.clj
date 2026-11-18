#!/usr/bin/env bb
;; What the Reading panel's scroll buttons run on the TV, and what /status says
;; about the page being read (2026-11-19, the ✥ Shift compass). Loads a COPY of
;; tvremote.clj in a temp folder whose config.edn points CDP at an unused port,
;; like cursor.clj, then stubs js, so no Chrome is touched. Run: bb test/scroll.clj
(require '[babashka.fs :as fs] '[clojure.test :as t :refer [deftest is testing]])
(let [src (str (fs/parent (fs/parent (fs/absolutize *file*))) "/tvremote.clj")
      dir (str (fs/create-temp-dir {:prefix "tvremote-scroll"}))]
  (spit (str dir "/config.edn") "{:cdp-port 9333}")
  (fs/copy src (str dir "/tvremote.clj"))
  (load-file (str dir "/tvremote.clj"))
  (fs/delete-tree dir))
(in-ns 'tvremote)
(require '[clojure.test :as t :refer [deftest is testing]])

(defn ran
  "The script page-scroll runs for how, with js stubbed."
  [how]
  (let [got (atom nil)]
    (with-redefs [js (fn [_ expr] (reset! got expr) true)]
      (page-scroll {:id "pg"} how))
    @got))

(deftest scroll-buttons
  (testing "Up, Down and Top, as before"
    (is (= "window.scrollBy({top:innerHeight*0.8,behavior:'smooth'});true" (ran "down")))
    (is (= "window.scrollBy({top:-innerHeight*0.8,behavior:'smooth'});true" (ran "up")))
    (is (= "window.scrollTo({top:0,behavior:'smooth'});true" (ran "top"))))
  (testing "✥ Shift: a tenth of the screen each way"
    (is (re-find #"top:-innerHeight\*0\.1" (ran "n")))
    (is (re-find #"top:innerHeight\*0\.1" (ran "s")))
    (is (re-find #"left:-innerWidth\*0\.1" (ran "w")))
    (is (re-find #"left:innerWidth\*0\.1" (ran "e")))))

(deftest status-wide
  (with-redefs [chrome-up? (constantly true) targets (constantly [{:id "pg" :type "page" :url "https://example.com/p" :title "P"}])
                page-id (atom "pg") rl-trail (constantly nil) recent (constantly []) yt-gone-ids (constantly [])
                load-state (constantly {}) save-page-id! (constantly nil)]
    (testing "Reading: :wide says whether the page is wider than the screen"
      (with-redefs [service (atom "page")]
        (with-redefs [js (fn [_ e] (= e page-wide-js))] (is (true? (:wide (status-2 "chris")))))
        (with-redefs [js (constantly false)] (is (false? (:wide (status-2 "chris")))))
        (with-redefs [js (fn [& _] (throw (ex-info "tab busy" {})))] (is (false? (:wide (status-2 "chris")))))))
    (testing "not Reading: no :wide"
      (with-redefs [service (atom "yt") status (constantly {:chrome true :service "yt"})]
        (is (not (contains? (status-2 "chris") :wide)))))))

(let [{:keys [fail error test pass]} (t/run-tests 'tvremote)]
  (println (str test " tests, " (+ pass fail error) " checks, " (+ fail error) " failing"))
  (System/exit (if (pos? (+ fail error)) 1 0)))
