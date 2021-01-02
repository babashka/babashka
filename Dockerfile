FROM ubuntu:latest

RUN apt-get update \
    && apt-get install -y curl \
    && mkdir -p /usr/local/bin

COPY bb /usr/local/bin/bb

CMD ["bb"]
