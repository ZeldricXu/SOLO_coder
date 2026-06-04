FROM alpine:3.19
RUN apk add --no-cache curl bash
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
CMD ["/entrypoint.sh"]
