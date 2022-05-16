# Deploying a babashka app to fly.io

[Fly.io](https://fly.io/) is a service that can run full stack apps with minimal
configuration. If you like the ease of Heroku, you might like fly.io and perhaps
even better! This document shows how to get a minimal babashka application up
and running on `fly.io`.

In `example.clj` we start an http-kit web server which spits out some HTML. You
can run this locally by invoking `bb example.clj` from the command line.

To get this site running on `fly.io`, you need to
[install](https://fly.io/docs/getting-started/installing-flyctl/) and [log
in](https://fly.io/docs/getting-started/log-in-to-fly/).

Then run `flyctl launch` to create a new application. After making changes, you
can re-deploy the site with `flyctl deploy`.

That's it!
