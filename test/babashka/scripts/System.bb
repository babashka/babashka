[(System/getProperty "user.dir")
 (System/getProperty "foo" "bar")
 (or (System/getenv "HOME") (System/getenv "HOMEPATH"))
 (System/getProperties)
 (System/getenv)]
