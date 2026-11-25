(require '[c3 :refer [parallel-filter]])

(defn prime? [^long n]
  (let [limit (long (Math/sqrt n))]
    (and (>= n 2)
         (loop [d 2]
           (cond
             (> d limit) true
             (zero? (rem n d)) false
             :else (recur (inc d)))))))

(let [numbers (vec (range 1000000000000 1000000016384))]
  (println "filter" (time (count (filter prime? numbers))))
  (println "parallel-filter"
           (time (count (parallel-filter prime? numbers 128 8)))))

(shutdown-agents)
