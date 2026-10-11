# syntax=docker/dockerfile:1
FROM bytedesk/freeswitch:latest

USER root

ARG HOST_IP

RUN mkdir -p /usr/local/freeswitch/share/freeswitch/sounds/poc \
    /usr/local/freeswitch/etc/freeswitch/dialplan/default

# Force FreeSWITCH RTP to use the same small range exposed by Docker
RUN sed -i -E \
    -e 's#^[[:space:]]*<!--[[:space:]]*<param name="rtp-start-port" value="[0-9]+"/>[[:space:]]*-->#    <param name="rtp-start-port" value="16384"/>#' \
    -e 's#^[[:space:]]*<!--[[:space:]]*<param name="rtp-end-port" value="[0-9]+"/>[[:space:]]*-->#    <param name="rtp-end-port" value="16484"/>#' \
    /usr/local/freeswitch/etc/freeswitch/autoload_configs/switch.conf.xml

# For this local Docker POC, advertise the Mac LAN IP instead of STUN/public IP
RUN sed -i -E \
    -e "s#<X-PRE-PROCESS cmd=\"(stun-set|set)\" data=\"external_rtp_ip=[^\"]*\"/>#<X-PRE-PROCESS cmd=\"set\" data=\"external_rtp_ip=${HOST_IP}\"/>#" \
    -e "s#<X-PRE-PROCESS cmd=\"(stun-set|set)\" data=\"external_sip_ip=[^\"]*\"/>#<X-PRE-PROCESS cmd=\"set\" data=\"external_sip_ip=${HOST_IP}\"/>#" \
    /usr/local/freeswitch/etc/freeswitch/vars.xml

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