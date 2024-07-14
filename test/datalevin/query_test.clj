(ns datalevin.query-test
  (:require
   [datalevin.test.core :as tdc :refer [db-fixture]]
   [clojure.test :refer [deftest is are use-fixtures testing]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.clojure-test :as test]
   [clojure.test.check.properties :as prop]
   [clojure.set :as set]
   [clojure.edn :as edn]
   [clojure.walk :as walk]
   [datalevin.core :as d]
   [datalevin.query :as sut]
   [datalevin.constants :as c]
   [datalevin.util :as u])
  (:import
   [java.util UUID]))

(use-fixtures :each db-fixture)

(deftest single-encla-test
  (let [dir (u/tmp-dir (str "single-encla-test-" (UUID/randomUUID)))
        db  (-> (d/empty-db dir
                            {:name   {:db/unique :db.unique/identity}
                             :friend {:db/valueType :db.type/ref}
                             :aka    {:db/cardinality :db.cardinality/many}
                             :school {:db/valueType :db.type/keyword}}
                            {:kv-opts
                             {:flags (conj c/default-env-flags :nosync)}})
                (d/db-with [{:db/id  1,        :name   "Ivan", :age 15
                             :aka    ["robot" "ai"]
                             :school :ny/union :friend 2}
                            {:db/id 2, :name "Petr", :age 37 :friend 3
                             :aka   ["ai" "pi"]}
                            { :db/id 3, :name "Oleg", :age 37
                             :aka    ["bigmac"]}
                            { :db/id 4, :name "John" :age 15 }]))]
    (is (= #{} (d/q '[:find ?e
                      :in $
                      :where
                      [?e :name "Ivan"]
                      [?e :age 17]]
                    db)))
    (is (= (:actual-result-size (d/explain {:run? true}
                                           '[:find ?e
                                             :in $
                                             :where
                                             [?e :name "Non-existent"]
                                             [?e :age 15]]
                                           db ))
           0))
    (is (empty? (d/q '[:find ?e
                       :in $
                       :where
                       [?e :name "Non-existent"]
                       [?e :age 2]]
                     db)))
    (is (= (set (d/q '[:find ?a
                       :in $
                       :where
                       [?e :age ?a]
                       [?e :aka "bigmac"]
                       [?e :name "Oleg"]]
                     db ))
           #{[37]}))
    (is (= (:actual-result-size (d/explain {:run? true}
                                           '[:find ?a
                                             :in $
                                             :where
                                             [?e :age ?a]
                                             [?e :aka "bigmac"]
                                             [?e :name "Oleg"]]
                                           db ))
           1))
    (is (= (d/q '[:find ?a
                  :in $ ?n
                  :where
                  [?e :friend]
                  [?e :name ?n]
                  [?e :age ?a]]
                db "Ivan")
           #{[15]}))
    (is (= [1] (d/q '[:find [?e ...]
                      :in $ ?ns-in
                      :where
                      [(namespace ?v) ?ns]
                      [(= ?ns ?ns-in)]
                      [?e :school ?v]]
                    db "ny")))
    (is (= (d/q '[:find ?e ?v
                  :in $ ?e
                  :where [?e :age ?v]]
                db [:name "Ivan"])
           #{[[:name "Ivan"] 15]}))
    (is (= (d/q '[:find ?v
                  :in $ ?e
                  :where [?e :age ?v]]
                db [:name "Ivan"])
           #{[15]}))
    (is (= (d/q '[:find ?v
                  :in $
                  :where [1 :age ?v]]
                db)
           #{[15]}))
    (is (= (d/q '[:find ?v ?s
                  :in $ ?e
                  :where
                  [?e :age ?v]
                  [?e :school ?s]]
                db [:name "Ivan"])
           #{[15 :ny/union]}))
    (is (= #{"robot" "ai" "bigmac" "pi"}
           (set (d/q '[:find [?aname ...]
                       :where
                       [_ :aka ?aname]]
                     db))))
    (is (= (d/q '[:find  ?a ?v
                  :in    $db ?e ?k
                  :where
                  [$db ?e ?a ?v]
                  [$db ?e :aka ?k]]
                db 1 "ai")
           #{[:name "Ivan"]
             [:age 15]
             [:friend 2]
             [:school :ny/union]
             [:aka "robot"]
             [:aka "ai"]}))
    (is (= (:actual-result-size (d/explain {:run? true}
                                           '[:find  ?a ?v
                                             :in    $db ?e ?k
                                             :where
                                             [$db ?e ?a ?v]
                                             [$db ?e :aka ?k]]
                                           db 1 "ai"))
           6))
    (is (= (d/q '[:find ?e
                  :where [?e :aka "ai"]] db)
           #{[1] [2]}))
    (is (= (d/q '[:find ?e
                  :where [?e :name]] db)
           #{[1] [2] [3] [4]}))
    (is (= (d/q '[:find  ?e ?v
                  :where
                  [?e :name "Ivan"]
                  [?e :age ?v]] db)
           #{[1 15]}))
    (is (= (d/q '[:find  ?a1
                  :where
                  [_ :age ?a1]
                  [(>= ?a1 22)]
                  [(odd? ?a1)]] db)
           #{[37]}))
    (is (= (:actual-result-size
            (d/explain {:run? true}
                       '[:find  ?a1
                         :where
                         [_ :age ?a1]
                         [(>= ?a1 22)]
                         [(odd? ?a1)]] db))
           1))
    (is (= (d/q '[:find  ?n ?a
                  :in ?k $
                  :where
                  [?e :aka ?k]
                  [?e :name ?n]
                  [?e :age  ?a]]
                "dragon_saver_94"
                [[1 :name "Ivan"]
                 [1 :age  19]
                 [1 :aka  "dragon_saver_94"]
                 [1 :aka  "-=autobot=-"]])
           #{["Ivan" 19]}))
    (is (nil? (:plan (d/explain {:run? true}
                                '[:find  ?n ?a
                                  :in ?k $
                                  :where
                                  [?e :aka ?k]
                                  [?e :name ?n]
                                  [?e :age  ?a]]
                                "dragon_saver_94"
                                [[1 :name "Ivan"]
                                 [1 :age  19]
                                 [1 :aka  "dragon_saver_94"]
                                 [1 :aka  "-=autobot=-"]]))))
    (is (= #{[3 :age 37] [2 :age 37] [4 :age 15] [1 :school :ny/union]}
           (d/q '[:find ?e ?a ?v
                  :where
                  [?e :name _]
                  [(get-some $ ?e :school :age) [?a ?v]]] db)))
    (is (= (:actual-result-size
            (d/explain {:run? true}
                       '[:find ?e ?a ?v
                         :where
                         [?e :name _]
                         [(get-some $ ?e :school :age) [?a ?v]]] db))
           4))
    (is (= (d/q '[:find  ?e ?a
                  :where
                  [?e :age ?a]
                  [?e :age 15]]
                db)
           #{[1 15] [4 15]}))
    (is (= (d/q '[:find  ?e
                  :in    $ ?adult
                  :where [?e :age ?a]
                  [(?adult ?a)]]
                db #(> ^long % 18))
           #{[2] [3]}))
    (is (= (:actual-result-size (d/explain {:run? true}
                                           '[:find  ?e
                                             :in    $ ?adult
                                             :where [?e :age ?a]
                                             [(?adult ?a)]]
                                           db #(> ^long % 18)))
           2))
    (is (= #{}
           (d/q '[:find ?name
                  :in $ ?my-fn
                  :where
                  [?e :name ?name]
                  [(?my-fn) ?result]
                  [(< ?result 3)]]
                db (fn [] 5))))
    (is (= 0
           (:actual-result-size (d/explain {:run? true} '[:find ?name
                                                          :in $ ?my-fn
                                                          :where
                                                          [?e :name ?name]
                                                          [(?my-fn) ?result]
                                                          [(< ?result 3)]]
                                           db (fn [] 5)))))

    (is (= (set (d/q '[:find ?a
                       :in $ ?n
                       :where
                       [?e :friend ?e1]
                       [?e :name ?n]
                       [?e1 :age ?a]]
                     db "Ivan"))
           #{[37]}))
    (is (= (d/q '[:find  ?e1 ?e2
                  :where
                  [?e1 :name ?n]
                  [?e2 :name ?n]] db)
           #{[1 1] [2 2] [3 3] [4 4] }))
    (is (= (d/q '[:find  ?e ?e2 ?n
                  :in $ ?i
                  :where
                  [?e :name ?i]
                  [?e :age ?a]
                  [?e2 :age ?a]
                  [?e2 :name ?n]] db "Ivan")
           #{[1 1 "Ivan"]
             [1 4 "John"]}))
    (is (= (d/q '[:find ?n
                  :in $ ?i
                  :where
                  [?e :name ?i]
                  [?e :age ?a]
                  [?e2 :age ?a2]
                  [(< ?a ?a2)]
                  [?e2 :name ?n]] db "Ivan")
           #{["Oleg"] ["Petr"]}))
    (is (= (d/q '[:find  ?n1 ?n2
                  :where
                  [?e1 :aka ?x]
                  [?e2 :aka ?x]
                  [?e1 :name ?n1]
                  [?e2 :name ?n2]] db)
           #{["Ivan" "Ivan"]
             ["Petr" "Petr"]
             ["Ivan" "Petr"]
             ["Petr" "Ivan"]
             ["Oleg" "Oleg"]}))
    (d/close-db db)
    (u/delete-files dir)))

(deftest rev-ref-test
  (let [dir (u/tmp-dir (str "rev-ref-test-" (UUID/randomUUID)))
        db  (-> (d/empty-db
                  dir
                  {:user/name      {:db/unique    :db.unique/identity
                                    :db/valueType :db.type/string}
                   :database/name  {:db/unique    :db.unique/identity
                                    :db/valueType :db.type/string}
                   :database/type  {:db/valueType :db.type/keyword}
                   :role/key       {:db/valueType :db.type/keyword
                                    :db/unique    :db.unique/identity}
                   :permission/act {:db/valueType :db.type/keyword}
                   :permission/obj {:db/valueType :db.type/keyword}
                   :permission/tgt {:db/valueType :db.type/ref}
                   :user-role/user {:db/valueType :db.type/ref}
                   :user-role/role {:db/valueType :db.type/ref}
                   :role-perm/role {:db/valueType :db.type/ref}
                   :role-perm/perm {:db/valueType :db.type/ref}}
                  {:kv-opts {:flags (conj c/default-env-flags :nosync)}})
                (d/db-with [{:db/id     -1
                             :user/name "datalevin"}
                            {:db/id    -2
                             :role/key :datalevin.role/datalevin}
                            {:db/id          -3
                             :user-role/user -1
                             :user-role/role -2}
                            {:db/id          -4
                             :permission/act ::control
                             :permission/obj ::server}
                            {:db/id          -5
                             :role-perm/perm -4
                             :role-perm/role -2}]))]
    (is (= (d/q '[:find (pull ?p [:permission/act :permission/obj])
                  :in $ ?uname
                  :where
                  [?u :user/name ?uname]
                  [?ur :user-role/user ?u]
                  [?ur :user-role/role ?r]
                  [?rp :role-perm/role ?r]
                  [?rp :role-perm/perm ?p]]
                db "datalevin")
           [[{:permission/obj :datalevin.query-test/server,
              :permission/act :datalevin.query-test/control}]]))
    (is (= (:actual-result-size
            (d/explain {:run? true}
                       '[:find (pull ?p [:permission/act :permission/obj])
                         :in $ ?uname
                         :where
                         [?u :user/name ?uname]
                         [?ur :user-role/user ?u]
                         [?ur :user-role/role ?r]
                         [?rp :role-perm/role ?r]
                         [?rp :role-perm/perm ?p]]
                       db "datalevin"))
           1))
    (is (= (d/q '[:find (pull ?p [:permission/act :permission/obj])
                  :in $ ?rk
                  :where
                  [?r :role/key ?rk]
                  [?ur :user-role/role ?r]
                  [?rp :role-perm/role ?r]
                  [?rp :role-perm/perm ?p]]
                db :datalevin.role/datalevin)
           [[{:permission/obj :datalevin.query-test/server,
              :permission/act :datalevin.query-test/control}]]))
    (is (= (:actual-result-size
            (d/explain {:run? true}
                       '[:find (pull ?p [:permission/act :permission/obj])
                         :in $ ?rk
                         :where
                         [?r :role/key ?rk]
                         [?ur :user-role/role ?r]
                         [?rp :role-perm/role ?r]
                         [?rp :role-perm/perm ?p]]
                       db :datalevin.role/datalevin))
           1))
    (d/close-db db)
    (u/delete-files dir)))

(deftest multiple-encla-test
  (let [dir (u/tmp-dir (str "multi-encla-test-" (UUID/randomUUID)))
        db  (-> (d/empty-db
                  dir
                  {:person/name   {:db/unique    :db.unique/identity
                                   :db/valueType :db.type/string}
                   :person/friend {:db/valueType   :db.type/ref
                                   :db/cardinality :db.cardinality/many}
                   :person/aka    {:db/cardinality :db.cardinality/many
                                   :db/valueType   :db.type/string}
                   :person/age    {:db/valueType :db.type/long}
                   :person/city   {:db/valueType :db.type/string}
                   :person/hobby  {:db/valueType   :db.type/string
                                   :db/cardinality :db.cardinality/many}
                   :person/school {:db/valueType   :db.type/ref
                                   :db/cardinality :db.cardinality/many}
                   :school/name   {:db/valueType :db.type/string
                                   :db/unique    :db.unique/identity}
                   :school/city   {:db/valueType :db.type/string}}
                  {:kv-opts {:flags (conj c/default-env-flags :nosync)}})
                (d/db-with [{:db/id         1,
                             :person/name   "Ivan",
                             :person/age    15
                             :person/aka    ["robot" "ai"]
                             :person/school 6
                             :person/city   "San Jose"
                             :person/hobby  ["video games" "chess"]
                             :person/friend 2}
                            {:db/id         2,
                             :person/name   "Petr",
                             :person/age    16
                             :person/aka    "fixer"
                             :person/school 7
                             :person/city   "Fremont"
                             :person/hobby  ["video games"]
                             :person/friend [1 3]}
                            {:db/id         3,
                             :person/name   "Oleg",
                             :person/city   "San Jose"
                             :person/age    22
                             :person/hobby  ["video games"]
                             :person/aka    ["bigmac"]
                             :person/friend [1 4 5]}
                            {:db/id         4,
                             :person/name   "John"
                             :person/school 7
                             :person/city   "San Jose"
                             :person/hobby  ["video games" "baseball"]
                             :person/age    15
                             :person/friend 5}
                            {:db/id         5,
                             :person/name   "James"
                             :person/school 7
                             :person/city   "Fremont"
                             :person/hobby  ["baseball"]
                             :person/age    16
                             :person/friend 4}
                            {:db/id       6
                             :school/city "San Jose"
                             :school/name "Leland"}
                            {:db/id       7
                             :school/city "Fremont"
                             :school/name "Mission"}]))]
    (is (= (set (d/q '[:find ?n1 ?n2
                       :in $ ?c
                       :where
                       [?e1 :person/city ?c]
                       [?e :school/city ?c]
                       [?e1 :person/name ?n1]
                       [?e1 :person/age ?a]
                       [?e2 :person/age ?a]
                       [?e2 :person/school ?e]
                       [?e2 :person/name ?n2]
                       [(not= ?n1 ?n2)]]
                     db "Fremont"))
           #{["James" "Petr"] ["Petr" "James"]}))
    (is (= (:actual-result-size (d/explain {:run? true}
                                           '[:find ?n1 ?n2
                                             :in $ ?c
                                             :where
                                             [?e1 :person/city ?c]
                                             [?e :school/city ?c]
                                             [?e1 :person/name ?n1]
                                             [?e1 :person/age ?a]
                                             [?e2 :person/age ?a]
                                             [?e2 :person/school ?e]
                                             [?e2 :person/name ?n2]
                                             [(not= ?n1 ?n2)]]
                                           db "Fremont"))
           2))
    (is (= (set (d/q '[:find ?n1
                       :in $ ?n
                       :where
                       [?e :person/school ?s]
                       [?e :person/name ?n]
                       [?e :person/city ?c1]
                       [?e1 :person/name ?n1]
                       [?e1 :person/school ?s]
                       [?e1 :person/city ?c2]
                       [(not= ?c1 ?c2)]
                       [?e :person/hobby ?h]
                       [?e1 :person/hobby ?h]]
                     db "John"))
           #{["James"] ["Petr"]}))
    (is (= (set (d/q '[:find ?n1
                       :in $ ?n
                       :where
                       [?e :person/school ?s]
                       [?e :person/name ?n]
                       [?e :person/city ?c]
                       [?e :person/hobby ?h]
                       [?e1 :person/name ?n1]
                       [?e1 :person/school ?s]
                       [?e1 :person/city ?c]
                       [?e1 :person/hobby ?h1]
                       [(not= ?h ?h1)]]
                     db "James"))
           #{["Petr"]}))
    (is (= (set (d/q '[:find ?n1
                       :in $ ?n
                       :where
                       [?e :person/school ?s]
                       [?e :person/name ?n]
                       [?e1 :person/school ?s]
                       [?e1 :person/name ?n1]]
                     db "John"))
           #{["James"] ["John"] ["Petr"]}))
    (is (= (set (d/q '[:find ?a
                       :in $ ?n
                       :where
                       [?e :person/friend ?e1]
                       [?e :person/name ?n]
                       [?e1 :person/age ?a]]
                     db "Ivan"))
           #{[16]}))
    (is (= (:actual-result-size (d/explain {:run? true}
                                           '[:find ?a
                                             :in $ ?n
                                             :where
                                             [?e :person/friend ?e1]
                                             [?e :person/name ?n]
                                             [?e1 :person/age ?a]]
                                           db "Ivan"))
           1))
    (is (= (d/q '[:find  ?e1 ?e2
                  :where
                  [?e1 :person/name ?n]
                  [?e2 :person/name ?n]] db)
           #{[1 1] [2 2] [3 3] [4 4] [5 5]}))
    (is (nil? (:actual-result-size (d/explain {}
                                              '[:find  ?e1 ?e2
                                                :where
                                                [?e1 :person/name ?n]
                                                [?e2 :person/name ?n]] db))))
    (is (= (d/q '[:find  ?e ?e2 ?n
                  :in $ ?i
                  :where
                  [?e :person/name ?i]
                  [?e :person/age ?a]
                  [?e2 :person/age ?a]
                  [?e2 :person/name ?n]] db "Ivan")
           #{[1 1 "Ivan"]
             [1 4 "John"]}))
    (is (= (:late-clauses (d/explain {:run? true}
                                     '[:find  ?e ?e2 ?n
                                       :in $ ?i
                                       :where
                                       [?e :person/name ?i]
                                       [?e :person/age ?a]
                                       [?e2 :person/age ?a]
                                       [?e2 :person/name ?n]] db "Ivan"))
           []))
    (is (= (d/q '[:find ?n
                  :in $ ?i
                  :where
                  [?e :person/name ?i]
                  [?e :person/age ?a]
                  [?e2 :person/age ?a2]
                  [(< ?a ?a2)]
                  [?e2 :person/name ?n]] db "Ivan")
           #{["Oleg"] ["Petr"] ["James"]}))
    (is (= (:actual-result-size (d/explain {:run? true}
                                           '[:find ?n
                                             :in $ ?i
                                             :where
                                             [?e :person/name ?i]
                                             [?e :person/age ?a]
                                             [?e2 :person/age ?a2]
                                             [(< ?a ?a2)]
                                             [?e2 :person/name ?n]] db "Ivan"))
           3))
    (d/close-db db)
    (u/delete-files dir)))

(deftest ranges-test
  (let [vs       (vec (range 20))
        vset     (set vs)
        select   #(into #{}
                        (comp (map (fn [[[_ l] [_ r]]] (subvec vs l r)))
                           cat)
                        %)
        to-range #(mapv (fn [[l r]] [[:closed l] [:closed r]]) %)]
    (testing "range combinations"
      (are [intervals]
          (let [ranges (to-range intervals)
                combined (sut/combine-ranges ranges)
                flipped (walk/postwalk
                          #(case %
                             :db.value/sysMax 20
                             :db.value/sysMin 0
                             %)
                          (sut/flip-ranges combined))]
            (= (select ranges)
               (select combined)
               (set/difference vset (select flipped))))
        [[1 4] [2 3] [7 8]]
        [[1 2] [3 4] [5 7]]
        [[2 9] [1 4] [2 5]]
        [[3 7] [1 4] [4 5]]
        [[6 6] [1 4] [2 4]]
        [[1 3] [1 4] [2 7]]
        [[1 5] [3 3] [1 4]]
        [[6 6] [1 4] [4 5]]
        [[1 3] [4 7] [2 4] [3 5]]
        [[1 2] [3 4] [3 5]]
        [[3 5] [2 7] [4 7] [8 9]]
        [[4 5] [1 2] [1 4]]
        [[1 2] [1 2] [4 5] [1 5]]
        [[7 9] [3 4] [1 2] [2 3]]))
    (testing "range insersections"
      (are [vs1 vs2 vs3]
          (let [r1 (to-range vs1)
                r2 (to-range vs2)
                r3 (to-range vs3)]
            (= (set/intersection (select r1) (select r2) (select r3))
               (select (sut/intersect-ranges r1 r2 r3))))
        [[1 6] [7 12]]         [[2 5] [8 11]] [[3 4] [9 10]]
        [[1 6] [7 12] [13 17]] [[2 5] [8 11]] [[3 4] [9 10]]
        [[1 3]]                [[1 4]]        [[2 6]]
        [[1 3]]                [[2 5]]        [[4 6]]
        [[1 2]]                [[2 3]]        [[3 4]]
        ))))

(test/defspec random-ranges-test
  100
  (prop/for-all
    [bases (gen/vector (gen/large-integer* {:min 1 :max 100}) 5)
     offsets (gen/vector (gen/large-integer* {:min 1 :max 50}) 5)
     targets (gen/vector-distinct gen/nat {:num-elements 10})]
    (let [ranges    (mapv (fn [b o] [[:open b] [:open (+ ^long b ^long o)]])
                          bases offsets)
          combined  (sut/combine-ranges ranges)
          in-range? (fn [ranges target]
                      (some (fn [[[_ l] [_ h]]] (<= l target h)) ranges))]
      (is (every? true?
                  (mapv (fn [x y] (= x y))
                        (mapv #(in-range? ranges %) targets)
                        (mapv #(in-range? combined %) targets)))))))

(deftest issue-259-260-test
  (let [dir (u/tmp-dir (str "issue-259-" (UUID/randomUUID)))
        db  (-> (d/empty-db dir
                            {:user/name  {:db/valueType :db.type/string}
                             :user/items {:db/valueType   :db.type/ref
                                          :db/cardinality :db.cardinality/many
                                          :db/isComponent true}
                             :item/name  {:db/valueType :db.type/string}})
                (d/db-with [{:user/name  "joe"
                             :user/items [{:item/name "pen"}]}
                            {:user/name  "larry"
                             :user/items [{:item/name "pen"}]}]))]
    (is (= #{[2]} (:result (d/explain {:run? true} '[:find ?e1
                                                     :where
                                                     [?u1 :user/name "joe"]
                                                     [?u1 :user/items ?e1]
                                                     [?u2 :user/items ?e2]
                                                     [?e1 :item/name ?n]
                                                     [4 :item/name ?n]]
                                      db))))
    (is (= #{[2]} (:result (d/explain {:run? true} '[:find ?e
                                                     :where
                                                     [1 :user/items ?e]
                                                     [?e :item/name "pen"]]
                                      db))))
    (is (= #{[2]} (d/q '[:find ?e
                         :where
                         [?u :user/name "joe"]
                         [?u :user/items ?e]
                         [?e :item/name "pen"]]
                       db)))
    (d/close-db db)
    (u/delete-files dir)))

(deftest extra-bound-var-test
  (let [dir (u/tmp-dir (str "extra-bound-" (UUID/randomUUID)))
        db  (-> (d/empty-db dir {:user/id     {:db/valueType :db.type/long
                                               :db/unique    :db.unique/identity}
                                 :entity/uuid {:db/valueType :db.type/uuid
                                               :db/unique    :db.unique/identity}
                                 :entity/user {:db/valueType :db.type/ref}
                                 :entity/item {:db/valueType :db.type/long}})
                (d/db-with [{:db/id 1 :user/id 1}
                            {:entity/uuid (UUID/randomUUID)
                             :entity/user [:user/id 1] :entity/item 5}
                            {:entity/uuid (UUID/randomUUID)
                             :entity/user [:user/id 1] :entity/item 5}]))]
    (is (= #{[2] [3]} (d/q '[:find ?e
                             :where
                             [?e :entity/user ?u]
                             [?e :entity/item 5]
                             [?u :user/id 1]]
                           db)))
    (d/close-db db)
    (u/delete-files dir)))

(deftest logic-pred-test
  (let [dir (u/tmp-dir (str "and-or-" (UUID/randomUUID)))
        db  (-> (d/empty-db dir {:text {:db/valueType :db.type/string}
                                 :line {:db/valueType :db.type/long}})
                (d/db-with [{:db/id 1 :text "床前明月光" :line 1}
                            {:db/id 2 :text "疑是地上霜" :line 2}
                            {:db/id 3 :text "举头望明月" :line 3}
                            {:db/id 4 :text "低头思故乡" :line 4}]))]
    (is (= #{[3]} (d/q '[:find ?e
                         :where
                         [?e :line ?l]
                         [?e :text ?t]
                         [(or (and (< 1 ?l) (like ?t "%月%"))
                              (and (odd? ?l) (like ?t "%头%")))]]
                       db)))
    (is (= #{[3]} (d/q '[:find ?e
                         :where
                         [?e :line ?l]
                         [?e :text ?t]
                         [(and (< 1 ?l) (like ?t "%月%"))]]
                       db)))
    (is (= #{[3]} (d/q '[:find ?e
                         :where
                         [?e :line ?l]
                         [(and (< 1 ?l) (odd? ?l))]]
                       db)))
    (is (= #{[3]} (d/q '[:find ?e
                         :where
                         [?e :text ?t]
                         [(and (like ?t "%月%")
                               (not-like ?t "%月光%"))]]
                       db)))
    (is (= #{[1][3][4]} (d/q '[:find ?e
                               :where
                               [?e :text ?t]
                               [(or (like ?t "%月%")
                                    (like ?t "%乡%"))]]
                             db)))
    (d/close-db db)
    (u/delete-files dir)))

(deftest issue-263-test
  (let [dir    (u/tmp-dir (str "issue-263-" (UUID/randomUUID)))
        schema {:type
                #:db{:valueType   :db.type/keyword,
                     :cardinality :db.cardinality/one,
                     :aid         3},
                :d/e
                #:db{:valueType   :db.type/ref,
                     :cardinality :db.cardinality/one,
                     :aid         4},
                :c/b
                #:db{:valueType   :db.type/ref,
                     :cardinality :db.cardinality/one,
                     :aid         5},
                :f/e
                #:db{:valueType   :db.type/ref,
                     :cardinality :db.cardinality/one,
                     :aid         6},
                :d/c
                #:db{:valueType   :db.type/ref,
                     :cardinality :db.cardinality/one,
                     :aid         7},
                :a/b
                #:db{:valueType   :db.type/ref,
                     :cardinality :db.cardinality/one,
                     :aid         8},
                :f/x
                #:db{:valueType   :db.type/ref,
                     :cardinality :db.cardinality/one,
                     :aid         9},
                :a/x
                #:db{:valueType   :db.type/ref,
                     :cardinality :db.cardinality/one,
                     :aid         10}}
        conn   (d/get-conn dir schema)]
    (d/transact! conn (edn/read-string (slurp "test/data/dump-maps.txt")))
    (is (= 89282
           (-> (d/entity @conn 89569)
               :a/x
               :db/id)))
    (is (= 17
           (d/q
             '[:find (count ?f) .
               :in $
               :where
               [89569 :a/x ?x]
               [89569 :a/b ?b]
               [?c :c/b ?b]
               [?d :d/c ?c]
               [?d :d/e ?e]
               [?f :f/e ?e]
               [?f :f/x ?x]] @conn)))
    (d/close conn)
    (u/delete-files dir)))
