(ns c2-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [c2 :as c2]))

(deftest first-primes-test
  (is (= [2 3 5 7 11 13 17 19 23 29]
         (take 10 (c2/prime-sequence)))))

(deftest composite-squares-test
  (let [primes (set (take 30 (c2/prime-sequence)))]
    (is (not-any? primes [4 9 25 49 121]))))

(deftest thousandth-prime-test
  (is (= 7919 (nth (c2/prime-sequence) 999))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'c2-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
