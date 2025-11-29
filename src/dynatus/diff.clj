(ns dynatus.diff)

(defn compute [desired actual]
  (cond
    (nil? actual)
    {:action :create :definition desired}

    (not= (:KeySchema desired)
          (get-in actual [:Table :KeySchema]))
    {:action :recreate :reason :key-schema
     :definition desired}

    :else
    {:action :noop :definition desired}))