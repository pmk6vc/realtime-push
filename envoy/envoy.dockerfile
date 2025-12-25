FROM envoyproxy/envoy:v1.33.14

# Install envsubst (gettext)
RUN apt-get update \
    && apt-get install -y --no-install-recommends gettext-base ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY envoy-entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]