FROM eclipse-temurin:8-jre-jammy@sha256:48a8129eda9d0c8a81c86d1837c125b63a5ee8558ba9565b6066516ae72a8d47

ARG VERSION
ARG NETBASE_DEB_SHA256=480bbd894069e5da7a25f070ea0ebaded7f64bdd34a119558cc2fe7137762a79
ARG TELNET_DEB_SHA256=96daeef97f2a42d64aa8fec930340835655cd9df3f105613ca7a7405ba9b51be
ENV COS_VERSION=${VERSION}
ENV VERSION=${VERSION}
ENV TZ=UTC

RUN curl -fLsS --retry 3 -o /tmp/netbase.deb \
        https://archive.ubuntu.com/ubuntu/pool/main/n/netbase/netbase_6.3_all.deb \
    && curl -fLsS --retry 3 -o /tmp/telnet.deb \
        https://archive.ubuntu.com/ubuntu/pool/main/n/netkit-telnet/telnet_0.17-44build1_amd64.deb \
    && printf '%s  %s\n' "$NETBASE_DEB_SHA256" /tmp/netbase.deb \
        "$TELNET_DEB_SHA256" /tmp/telnet.deb | sha256sum -c - \
    && dpkg -i /tmp/netbase.deb /tmp/telnet.deb \
    && ln -sf /bin/bash /bin/sh \
    && rm -f /tmp/netbase.deb /tmp/telnet.deb

COPY cosbench-sineio.tar.gz /tmp/cosbench-sineio.tar.gz
RUN tar -xzf /tmp/cosbench-sineio.tar.gz -C / \
    && mv "/cosbench-${VERSION}-sineio" /cosbench \
    && rm /tmp/cosbench-sineio.tar.gz

COPY start-cosbench.sh /cosbench/start-cosbench.sh
WORKDIR /cosbench

CMD ["bash", "/cosbench/start-cosbench.sh"]
