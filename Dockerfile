# syntax=docker/dockerfile:1
FROM bytedesk/freeswitch:latest

USER root

RUN mkdir -p /usr/local/freeswitch/share/freeswitch/sounds/poc \
    /usr/local/freeswitch/etc/freeswitch/dialplan/default

COPY generated/menu.wav    /usr/local/freeswitch/share/freeswitch/sounds/poc/menu.wav
COPY generated/sales.wav   /usr/local/freeswitch/share/freeswitch/sounds/poc/sales.wav
COPY generated/support.wav /usr/local/freeswitch/share/freeswitch/sounds/poc/support.wav
COPY generated/invalid.wav /usr/local/freeswitch/share/freeswitch/sounds/poc/invalid.wav

COPY generated/99-mini-ivr.xml \
     /usr/local/freeswitch/etc/freeswitch/dialplan/default/99-mini-ivr.xml

EXPOSE 5060/tcp
EXPOSE 5060/udp
EXPOSE 8021/tcp
EXPOSE 16384-16484/udp
