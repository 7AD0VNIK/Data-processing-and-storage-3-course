(ns c3)

 (defn parallel-filter [pred xs block-size workers]
   (letfn [(step [batches]
             (lazy-seq
              (when-let [batches (seq batches)]
                (let [tasks (mapv (fn [block]
                                    (future (filterv pred block)))
                                  (first batches))]
                  (concat (mapcat deref tasks)
                          (step (rest batches)))))))]
     (step (partition-all workers (partition-all block-size xs)))))