#!/usr/bin/env bb
;; Which tabs the 3s tick hides the mouse pointer on (2026-11-19). Loads a COPY
;; of tvremote.clj in a temp folder whose config.edn points CDP at an unused
;; port, like state_race.clj, then stubs targets / js / cdp, so no Chrome is
;; touched. Run: bb test/cursor.clj
(require '[babashka.fs :as fs] '[babashka.process] '[clojure.test :as t :refer [deftest is testing]])
(let [src (str (fs/parent (fs/parent (fs/absolutize *file*))) "/tvremote.clj")
      dir (str (fs/create-temp-dir {:prefix "tvremote-cursor"}))]
  (spit (str dir "/config.edn") "{:cdp-port 9333}")
  (fs/copy src (str dir "/tvremote.clj"))
  (load-file (str dir "/tvremote.clj"))
  (fs/delete-tree dir))
(in-ns 'tvremote)
(require '[babashka.fs :as fs] '[babashka.process] '[clojure.test :as t :refer [deftest is testing]])

(def tabs
  [{:id "nf" :type "page" :url "https://www.netflix.com/watch/1"}
   {:id "yt" :type "page" :url "https://www.youtube.com/watch?v=x"}
   {:id "pg" :type "page" :url "https://example.com/post"}
   {:id "rl" :type "page" :url "https://other.example.org/linked"}
   {:id "rv" :type "page" :url "https://www.youtube-nocookie.com/embed/y"}
   {:id "xx" :type "page" :url "https://accounts.example.net/somewhere"}
   {:id "sw" :type "service_worker" :url "https://example.com/sw.js"}])

(defn run-tick
  "Calls f with targets/js/cdp stubbed. js answers with (answer tab expr).
   Returns {:js [tab ids js ran on] :moves [[tab-id params]] :nudges n}, n
   the real pointer nudges asked for."
  [f {:keys [locked answer] :or {answer (constantly true)}}]
  (let [ran (atom []) moves (atom []) nudges (atom 0)]
    (with-redefs [targets (constantly tabs)
                  locked? (constantly locked)
                  js (fn [t expr] (swap! ran conj (:id t)) (answer t expr))
                  cdp (fn [t method params]
                        (when (= "Input.dispatchMouseEvent" method)
                          (swap! moves conj [(:id t) params]))
                        nil)
                  nudge-pointer! (fn [] (swap! nudges inc))]
      (f))
    {:js @ran :moves @moves :nudges @nudges}))

(deftest hide-cursor-2-tabs
  (testing "locked: Netflix and YouTube only, the style put in on both"
    (let [r (run-tick hide-cursor-2! {:locked true :answer (constantly "added")})]
      (is (= ["nf" "yt"] (:js r)))
      (is (= [["nf" {:type "mouseMoved" :x 5 :y 5}] ["yt" {:type "mouseMoved" :x 5 :y 5}]] (:moves r)))))
  (testing "style already there: no made-up move"
    (is (= [] (:moves (run-tick hide-cursor-2! {:locked true :answer (constantly true)})))))
  (testing "unlocked: the same two tabs get the style taken out, no move"
    (let [r (run-tick hide-cursor-2! {:locked false :answer (constantly true)})]
      (is (= ["nf" "yt"] (:js r)))
      (is (= [] (:moves r)))))
  (testing "the script it runs says on or off"
    (is (re-find #"cursor:none" (nocursor-js-2 true)))
    (is (not= (nocursor-js-2 true) (nocursor-js-2 false)))))

(deftest idle-cursor-tabs
  (with-redefs [page-id (atom "pg") rl-id (atom "rl") rv-id (atom "rv")]
    (testing "locked or not: only the three reading tabs, never Netflix, YouTube or others"
      (is (= ["pg" "rl" "rv"] (:js (run-tick idle-cursor! {:locked false}))))
      (is (= ["pg" "rl" "rv"] (:js (run-tick idle-cursor! {:locked true})))))
    (testing "the one made-up move, at the place the page answers, only where it just hid"
      (let [r (run-tick idle-cursor! {:answer (fn [t _] (if (= "rl" (:id t)) {:x 40 :y 70} true))})]
        (is (= [["rl" {:type "mouseMoved" :x 40 :y 70}]] (:moves r)))))
    (testing "no move while the page answers true"
      (is (= [] (:moves (run-tick idle-cursor! {})))))
    (testing "no reading tab remembered: nothing runs"
      (with-redefs [page-id (atom nil) rl-id (atom nil) rv-id (atom nil)]
        (is (= [] (:js (run-tick idle-cursor! {})))))))
  (testing "the tick still hides on Netflix and YouTube as before"
    (is (= ["nf" "yt"] (:js (run-tick hide-cursor-2! {:locked true}))))))

(deftest idle-cursor-script
  (testing "the script is valid JavaScript"
    (let [f (str (fs/create-temp-file {:suffix ".js"}))]
      (spit f idle-cursor-js)
      (is (zero? (:exit (babashka.process/shell {:continue true} "node" "--check" f))))
      (fs/delete f)))
  (testing "it hides after 3s and answers the place for the made-up move"
    (is (re-find #"setTimeout\(hide,3000\)" idle-cursor-js))
    (is (re-find #"return \{x:w\.x,y:w\.y\}" idle-cursor-js))))

(deftest real-nudge
  (testing "each made-up move also gets one real nudge"
    (is (= 2 (:nudges (run-tick hide-cursor-2! {:locked true :answer (constantly "added")}))))
    (with-redefs [page-id (atom "pg") rl-id (atom "rl") rv-id (atom "rv")]
      (is (= 1 (:nudges (run-tick idle-cursor! {:answer (fn [t _] (if (= "rl" (:id t)) {:x 40 :y 70} true))}))))))
  (testing "no made-up move: no nudge either"
    (is (= 0 (:nudges (run-tick hide-cursor-2! {:locked true :answer (constantly true)}))))
    (is (= 0 (:nudges (run-tick hide-cursor-2! {:locked false}))))
    (with-redefs [page-id (atom "pg") rl-id (atom "rl") rv-id (atom "rv")]
      (is (= 0 (:nudges (run-tick idle-cursor! {}))))))
  (testing "the page ignores moves for 1.5s after it asks for the nudge"
    (is (re-find #"w\.quiet=Date\.now\(\)\+1500" idle-cursor-js))
    (is (re-find #"if\(Date\.now\(\)<w\.quiet\)" idle-cursor-js)))
  (testing "without :input-lock nothing is started"
    (with-redefs [input-lock? false nudge-proc (atom nil)]
      (is (nil? (nudge-pointer!)))
      (is (nil? @nudge-proc)))))

(let [{:keys [fail error test pass]} (t/run-tests 'tvremote)]
  (println (str test " tests, " (+ pass fail error) " checks, " (+ fail error) " failing"))
  (System/exit (if (pos? (+ fail error)) 1 0)))

