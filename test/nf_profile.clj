#!/usr/bin/env bb
;; Each person's Netflix profile (2026-11-27). A Netflix action by someone whose
;; :netflix-profile differs from the one the Netflix tab is on goes through
;; "Who's watching?" first. Loads a COPY of tvremote.clj with a test config,
;; like page_video.clj, and stubs the tab, so no Chrome is touched.
;; Run: bb test/nf_profile.clj
(require '[babashka.fs :as fs] '[clojure.test :as t :refer [deftest is testing]])
(let [src (str (fs/parent (fs/parent (fs/absolutize *file*))) "/tvremote.clj")
      dir (str (fs/create-temp-dir {:prefix "tvremote-nfp"}))]
  (spit (str dir "/config.edn")
        (pr-str {:cdp-port 9333
                 :people [{:name "Chris" :netflix-profile "Christopher"}
                          {:name "Rica"}
                          {:name "Zyra" :child? true :netflix-profile "Kids"}]}))
  (fs/copy src (str dir "/tvremote.clj"))
  (load-file (str dir "/tvremote.clj"))
  (fs/delete-tree dir))
(in-ns 'tvremote)
(require '[clojure.test :as t :refer [deftest is testing]] '[clojure.string :as str])

(def tab {:id "nf" :type "page" :url "https://www.netflix.com/browse"})
(def tiles [{:i 0 :name "Christopher"} {:i 1 :name "Raymond"} {:i 2 :name "Kids"}])

(defn fake-tab
  "A Netflix tab on profile `active`. Every step is logged in calls, in order:
   [:navigate url] [:pick i] [:browse url] [:switch svc user] [:resume svc]."
  [active]
  (let [calls (atom []) st (atom {:active active :gate false})]
    {:calls calls
     :redefs
     {#'wake-screen! (fn [] nil)
      #'keep-awake! (fn [_] nil)
      #'chrome-up? (fn [] true)
      #'tv-target (fn [] tab)
      #'pause-youtube! (fn [] nil)
      #'record-watching-2! (fn [] nil)
      #'set-owner! (fn [& _] nil)
      #'set-opened! (fn [& _] nil)
      #'cdp (fn [& _] {})
      #'navigate (fn [_ url] (swap! calls conj [:navigate url])
                   (when (str/includes? url "/ProfilesGate") (swap! st assoc :gate true)))
      #'browse (fn [url] (swap! calls conj [:browse url]) {:kind "titles"})
      #'switch-service-2! (fn [svc user] (swap! calls conj [:switch svc user]) {:ok true})
      #'resume! (fn [svc & _] (swap! calls conj [:resume svc]) {:ok true})
      #'js (fn [_ s]
             (cond
               (= s profiles-js) (when (:gate @st) tiles)
               (= s kind-js) (if (:gate @st) "profiles" "titles")
               (str/includes? s "userInfo")
               (if (:gate @st) {:gate true} {:gate false :name (:active @st)})
               (str/includes? s "(()=>!document.querySelector") (not (:gate @st))
               (str/includes? s ".click()")
               (let [i (parse-long (second (re-find #"\]\[(\d+)\]" s)))]
                 (swap! calls conj [:pick i])
                 (swap! st assoc :gate false :active (:name (nth tiles i)))
                 true)
               :else nil))}}))

(defmacro with-tab [[calls active] & body]
  `(let [f# (fake-tab ~active) ~calls (:calls f#)]
     (with-redefs-fn (:redefs f#) (fn [] (binding [*out* (java.io.StringWriter.)] ~@body)))))

(deftest unchanged-when-no-profile-to-switch
  (testing "someone with no :netflix-profile: browse just browses"
    (with-tab [calls "Kids"]
      (act "browse" {:u "rica"})
      (is (= [[:browse "https://www.netflix.com/browse"]] @calls))))
  (testing "already on their profile: browse just browses"
    (with-tab [calls "Christopher"]
      (act "browse" {:u "chris"})
      (is (= [[:browse "https://www.netflix.com/browse"]] @calls))))
  (testing "no one on the phone: nothing is switched"
    (with-tab [calls "Kids"]
      (act "shows" {})
      (is (= [[:browse "https://www.netflix.com/browse/genre/83"]] @calls)))))

(def gate [:navigate "https://www.netflix.com/ProfilesGate"])

(deftest switches-to-their-profile
  (testing "Chris on the Kids profile: Who's watching?, Christopher, then the browse"
    (with-tab [calls "Kids"]
      (act "browse" {:u "chris"})
      (is (= [gate [:pick 0] [:browse "https://www.netflix.com/browse"]] @calls))))
  (testing "Zyra on Christopher: Kids is picked before her last show is loaded"
    (with-tab [calls "Christopher"]
      (act "switch" {:to "nf" :u "zyra"})
      (is (= [gate [:pick 2] [:switch "nf" "zyra"]] @calls))))
  (testing "a Netflix Recent entry goes through the switch; a YouTube one does not"
    (with-tab [calls "Kids"]
      (act "resume" {:svc "nf" :i "0" :u "chris"})
      (is (= [gate [:pick 0] [:resume "nf"]] @calls)))
    (with-tab [calls "Kids"]
      (act "resume" {:svc "yt" :i "0" :u "chris"})
      (is (= [[:resume "yt"]] @calls))))
  (testing "a title played from the list switches first, then plays"
    (with-tab [calls "Kids"]
      (act "play" {:id "82038472" :t "Dead-End Job" :u "chris"})
      (is (= [gate [:pick 0] [:navigate "https://www.netflix.com/watch/82038472"]] @calls))))
  (testing "already on Who's watching?: the profile is picked there, no extra trip"
    (let [f (fake-tab "Kids") calls (:calls f)]
      (with-redefs-fn (:redefs f)
        (fn [] (binding [*out* (java.io.StringWriter.)]
                 (navigate tab "https://www.netflix.com/ProfilesGate")
                 (reset! calls [])
                 (act "home" {:u "chris"}))))
      (is (= [[:pick 0] [:navigate "https://www.netflix.com/browse"]] @calls))))
  (testing "the phone's own profile pick is left alone"
    (with-tab [calls "Kids"]
      (with-redefs [pick-profile (fn [i] (swap! calls conj [:picked-on-phone i]) {:kind "titles"})]
        (act "profile" {:i "2" :u "chris"}))
      (is (= [[:picked-on-phone 2]] @calls)))))

(deftest profile-missing
  (testing "their profile isn't on Who's watching?: nothing is clicked, the action still runs"
    (let [f (fake-tab "Kids")]
      (with-redefs-fn (merge (:redefs f) {#'nf-profile-of (fn [_] "Grandma")})
        (fn [] (binding [*out* (java.io.StringWriter.)] (act "browse" {:u "chris"}))))
      (is (= [gate [:browse "https://www.netflix.com/browse"]] @(:calls f))))))

(deftest profile-names
  (is (= "Christopher" (nf-profile-of "chris")))
  (is (= "Kids" (nf-profile-of "zyra")))
  (is (nil? (nf-profile-of "rica")))
  (is (nil? (nf-profile-of nil))))

(let [{:keys [fail error test pass]} (t/run-tests 'tvremote)]
  (println (str test " tests, " (+ pass fail error) " checks, " (+ fail error) " failing"))
  (System/exit (if (zero? (+ fail error)) 0 1)))
