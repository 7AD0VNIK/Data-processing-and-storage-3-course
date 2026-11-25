(ns c1)

(defn valid-strings [alphabet n]
  (reduce
   (fn [strings _]
     (reduce
      (fn [result suffix]
        (concat result
                (map #(str % suffix)
                     (remove #(= (first %) (first suffix))
                             alphabet))))
      '()
      strings))
   '("")
   (range n)))
