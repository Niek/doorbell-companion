FROM ghcr.io/home-assistant/home-assistant:2026.11.3

COPY configuration.yaml /usr/local/share/review-ha/configuration.yaml
COPY go2rtc.yaml /usr/local/share/review-ha/go2rtc.yaml
COPY --chmod=755 start.sh /usr/local/bin/start-review-ha

RUN mkdir -p /media && ffmpeg -hide_banner -loglevel error \
    -f lavfi -i testsrc2=size=640x360 -frames:v 1 -q:v 4 /media/review_doorbell.jpg \
    && ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=640x360:rate=15 \
    -t 12 -c:v libx264 -profile:v baseline -pix_fmt yuv420p -g 15 \
    /media/review_doorbell.mp4

ENTRYPOINT ["/usr/local/bin/start-review-ha"]
