(ns c2)

(defn- sieve [candidate table]
  (lazy-seq
   (loop [number candidate
          scheduled table]
     (if-let [steps (get scheduled number)]
       (let [next-table
             (reduce
              (fn [result step]
                (update result (+' number step) (fnil conj []) step))
              (dissoc scheduled number)
              steps)]
         (recur (inc' number) next-table))
       (cons number
             (sieve (inc' number)
                    (assoc scheduled (*' number number) [number])))))))

(defn prime-sequence []
  (sieve 2 {}))