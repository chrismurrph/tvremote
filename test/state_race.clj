#!/usr/bin/env bb
;; Falsifier harness (2026-10-11): do concurrent writers to state.edn lose
;; data? Loads a COPY of tvremote.clj in a temp folder whose config.edn points
;; CDP at an unused port, so no Chrome is touched and the real state.edn isn't
;; either. Every state writer is load-state -> assoc -> spit, and load-state
;; returns {} on a read error, so a torn read can wipe the file.
;; Run: bb test/state_race.clj [threads] [writes-per-thread]
(require '[babashka.fs :as fs])
(let [src  (str (fs/parent (fs/parent (fs/absolutize *file*))) "/tvremote.clj")
      dir  (str (fs/create-temp-dir {:prefix "tvremote-race"}))
      n    (parse-long (or (first *command-line-args*) "8"))
      per  (parse-long (or (second *command-line-args*) "50"))]
  (spit (str dir "/config.edn") "{:cdp-port 9333}")
  (fs/copy src (str dir "/tvremote.clj"))
  (load-file (str dir "/tvremote.clj"))
  (in-ns 'tvremote)
  (let [state-file (resolve 'tvremote/state-file)
        load-state (resolve 'tvremote/load-state)
        add-history! (resolve 'tvremote/add-history!)
        set-opened! (resolve 'tvremote/set-opened!)]
    (spit @state-file (pr-str {:zoom {"chris" 1.2} :reading "https://example.com/keep"}))
    (let [wipes (atom 0)
          stop  (atom false)
          ;; a reader, like /status every 3s, counting reads that came back empty
          reader (future (while (not @stop)
                           (when (empty? (load-state)) (swap! wipes inc))))
          ws (doall (for [i (range n)]
                      (future (dotimes [j per]
                                (if (even? i)
                                  (add-history! (str "u" i) :page {:key (str "p" j) :url (str "p" j)})
                                  (set-opened! "page" (str "u" i) (str "k" j)))))))]
      (run! deref ws) (reset! stop true) @reader
      (let [st (load-state)
            hist (reduce + (for [i (range n) :when (even? i)] (count (get-in st [:history (str "u" i) :page]))))
            want (* per (count (filter even? (range n))))]
        (println "history entries kept:" hist "of" want)
        (println ":zoom survived:" (= 1.2 (get-in st [:zoom "chris"])) " :reading survived:" (some? (:reading st)))
        (println "empty reads seen by the reader:" @wipes)
        (fs/delete-tree dir)))))
