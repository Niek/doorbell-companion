#!/bin/sh
set -eu
umask 077

if [ ! -e /config/configuration.yaml ]; then
    cp /usr/local/share/review-ha/configuration.yaml /config/configuration.yaml
    printf '[]\n' > /config/automations.yaml
fi

if ! grep -q '^go2rtc:' /config/configuration.yaml; then
    printf '\ngo2rtc:\n  url: http://127.0.0.1:1984\n' >> /config/configuration.yaml
fi

(cd / && exec go2rtc -config /usr/local/share/review-ha/go2rtc.yaml) &

# Fly already supplies PID 1, which the upstream s6 init requires for itself.
exec python3 -m homeassistant --config /config
