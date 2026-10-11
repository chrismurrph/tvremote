#!/usr/bin/env bb
;; Falsifier harness (2026-10-11): the realistic case of state_race.clj - just
;; TWO saves at the same moment (a button press landing on the 15s recorder),
;; repeated. Counts how often the pair wipes keys neither of them wrote.
;; Run: bb test/state_race_pair.clj [trials]
(require '[babashka.fs :as fs])
(let [src (str (fs/parent (fs/parent (fs/absolutize *file*))) "/tvremote.clj")
      dir (str (fs/create-temp-dir {:prefix "tvremote-pair"}))
      trials (parse-long (or (first *command-line-args*) "500"))]
  (spit (str dir "/config.edn") "{:cdp-port 9333}")
  (fs/copy src (str dir "/tvremote.clj"))
  (load-file (str dir "/tvremote.clj"))
  (in-ns 'tvremote)
  (let [state-file @(resolve 'tvremote/state-file)
        load-state (resolve 'tvremote/load-state)
        add-history! (resolve 'tvremote/add-history!)
        set-opened! (resolve 'tvremote/set-opened!)
        ;; a state file the size of a long-used one: 100 pages each for two people
        big (into {:zoom {"chris" 1.2} :reading "https://example.com/keep"}
                  {:history (into {} (for [u ["chris" "rica"]]
                                       [u {:page (vec (for [i (range 100)] {:key (str "https://example.com/" i) :url (str "https://example.com/" i) :title (str "Page " i) :scroll 0.5 :zoom 1.2}))}]))})
        lost-one (atom 0) wiped (atom 0)]
    (dotimes [_ trials]
      (spit state-file (pr-str big))
      (let [go (promise)
            a (future @go (add-history! "chris" :yt {:key "v" :id "v"}))
            b (future @go (set-opened! "yt" "chris" "v"))]
        (deliver go true) @a @b
        (let [st (load-state)]
          (when-not (= 1.2 (get-in st [:zoom "chris"])) (swap! wiped inc))
          (when-not (and (get-in st [:history "chris" :yt]) (get-in st [:opened "yt"])) (swap! lost-one inc)))))
    (println "trials:" trials "  file size:" (count (pr-str big)) "bytes")
    (println "one of the two saves lost:" @lost-one)
    (println "everything else wiped (text size, history...):" @wiped)
    (fs/delete-tree dir)))
