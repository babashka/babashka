-- src/princess_bride/db/sql/characters.sql
-- The Princess Bride Characters

-- :name create-characters-table
-- :command :execute
-- :result :raw
-- :doc Create characters table
--  auto_increment and current_timestamp are
--  H2 Database specific (adjust to your DB)
create table characters (
  id         integer auto_increment primary key,
  name       varchar(40),
  specialty  varchar(40),
  created_at timestamp not null default current_timestamp
)

/* ...snip... */

-- A :result value of :n below will return affected rows:
-- :name insert-character :! :n
-- :doc Insert a single character returning affected row count
insert into characters (name, specialty)
values (:name, :specialty)

-- :name insert-characters :! :n
-- :doc Insert multiple characters with :tuple* parameter type
insert into characters (name, specialty)
values :tuple*:characters

/* ...snip... */

-- A ":result" value of ":1" specifies a single record
-- (as a hashmap) will be returned
-- :name character-by-id :? :1
-- :doc Get character by id
select * from characters
where id = :id

-- Let's specify some columns with the
-- identifier list parameter type :i* and
-- use a value list parameter type :v* for IN()
-- :name characters-by-ids-specify-cols :? :*
-- :doc Characters with returned columns specified
select :i*:cols from characters
where id in (:v*:ids)
