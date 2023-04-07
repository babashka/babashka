# User Middleware

- Your middleware is defined in bb user sources.
- Middleware is a function in wrap-middleware style.

# Example

Middleware should be a function in the form of:

```clojure
(defn my-middleware [handler]
  (fn [request]
    ;; ...
    (handler request)
    ;; ...
    ))
```

# Usage

```shell
bb nrepl-server --middleware [my.middleware/println-middleware]
```

--middleware is a vector of fully qualified function symbols.
They are required to be located on the babashka classpath.

This will start babashka with a nrepl server with the middlware defined in `user_middleware/my_middleware/src/my/middleware.clj`.

You can now connect to the nrepl like usual.

It is possible to redefine the middleware function from within the running nrepl, because we keep a reference
to the sci-var of the middlware.
