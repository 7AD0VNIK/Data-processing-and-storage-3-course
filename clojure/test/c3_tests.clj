(require '[clojure.test :refer [deftest is run-tests]])
(refer 'c3 :only '[parallel-filter])

(deftest finite-input
  (is (= [5 3 3 1]
         (vec (parallel-filter odd? [5 8 3 4 3 6 1] 2 2))))
  (is (empty? (parallel-filter odd? [] 2 2))))

(deftest infinite-input
  (is (= [1 3 5 7 9]
         (vec (take 5 (parallel-filter odd? (iterate inc 0) 2 2))))))

(deftest lazy-input
  (let [calls (atom 0)
        result (parallel-filter (fn [_] (swap! calls inc) true)
                                (iterate inc 0) 2 2)]
    (is (zero? @calls))
    (is (= [0 1 2 3] (vec (take 4 result))))
    (is (= 4 @calls))))

(run-tests)
