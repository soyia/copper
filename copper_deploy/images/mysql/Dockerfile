# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e
# The official MySQL 8.4 image currently bundles gosu built with Go 1.24.6.
# Keep the exact upstream MySQL index, but replace only that privilege-drop
# helper with the maintained Oracle Linux util-linux implementation.
FROM mysql:8.4.10@sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af

# Oracle ELSA-2026-88459: fixed eMudhra distrust bundle, identical noarch RPM
# for supported CPU architectures. Its SHA256 is published by Oracle.
ARG TARGETARCH
RUN rpm -e mysql-shell \
    && ! rpm -q mysql-shell \
    && ! command -v mysqlsh \
    && rm -rf /usr/lib/mysqlsh /root/.mysqlsh \
    && test ! -e /usr/lib/mysqlsh \
    && test ! -e /root/.mysqlsh \
    && curl --fail --location --silent --show-error \
      https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/ca-certificates-2026.2.92_v9.0.319-90.3.el9_8.noarch.rpm \
      --output /tmp/copper-ca-certificates.rpm \
    && printf '%s  %s\n' c69abcbaa031fddd4819ee451738ab28dfc4aae27c3f32d42e18c5bf61ad93ce /tmp/copper-ca-certificates.rpm | sha256sum --check --strict - \
    && rpm -Uvh /tmp/copper-ca-certificates.rpm \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' ca-certificates)" = 2026.2.92_v9.0.319-90.3.el9_8 \
    && update-ca-trust \
    && rm -f /tmp/copper-ca-certificates.rpm

# Copper uses mysql/mysqldump/mysqlbinlog, never the separate mysqlsh runtime.
# Removing that unused RPM leaves unowned Python caches created upstream.
# Remove its exact unused runtime/log directories, including certifi bytecode.
# Apply only ordinary OL9 security updates; never enter the epoch-10 FIPS stream.
# Preserve every installed mysql* RPM and the actual server/client ELF bytes.
RUN set -eu; \
    rpm -qa --qf '%{NAME} %{EPOCHNUM}:%{VERSION}-%{RELEASE}.%{ARCH}\n' > /tmp/copper-rpms.before \
    && awk '$1 ~ /^mysql/ {print}' /tmp/copper-rpms.before | LC_ALL=C sort > /tmp/copper-mysql.before \
    && test -s /tmp/copper-mysql.before \
    && sha256sum "$(command -v mysqld)" "$(command -v mysql)" "$(command -v mysqldump)" > /tmp/copper-mysql-cli.sha256 \
    && microdnf install -y --setopt=install_weak_deps=0 cpio util-linux \
    && microdnf upgrade -y --setopt=install_weak_deps=0 --disablerepo='*' --enablerepo=ol9_baseos_latest \
      openssl openssl-libs curl libcurl libevent glib2 libxml2 sqlite-libs pcre2 pcre2-syntax libacl \
    && for requirement in \
      openssl=1:3.5.8-2.0.1.el9_8 openssl-libs=1:3.5.8-2.0.1.el9_8 \
      curl=0:7.76.1-40.el9_8.7 libcurl=0:7.76.1-40.el9_8.7 \
      libevent=0:2.1.13-1.el9_8 glib2=0:2.68.4-19.el9_8.2 \
      libxml2=0:2.9.13-14.el9_8.5 sqlite-libs=0:3.34.1-11.el9_8 \
      pcre2=0:10.40-7.0.1.el9_8 pcre2-syntax=0:10.40-7.0.1.el9_8 libacl=0:2.4.0-1.el9_8; do \
        package="${requirement%%=*}"; minimum="${requirement#*=}"; \
        installed="$(rpm -q --qf '%{EPOCHNUM}:%{VERSION}-%{RELEASE}' "$package")" || exit 1; \
        case "$installed" in ''|*[!A-Za-z0-9:._+~^-]*|10:*|*_fips*) echo "invalid ordinary OL9 package: $package" >&2; exit 1 ;; esac; \
        comparison="$(rpm --eval "%{lua:print(rpm.vercmp('$installed', '$minimum'))}")" || exit 1; \
        case "$comparison" in 0|1) ;; *) echo "security update unavailable: $package ($installed < $minimum)" >&2; exit 1 ;; esac; \
        printf 'COPPER_OL9_SECURITY %s %s\n' "$package" "$installed"; \
      done \
    && case "$TARGETARCH" in \
         amd64) rpm_arch=x86_64; rpm_sha=6b4ab8b6f711a0f8460a411049ee7418c214aaa5bf64014c08e256db65ade69b ;; \
         arm64) rpm_arch=aarch64; rpm_sha=28f32b4f932cb84ad3bb6e87b8cd26c02ce76d90b1082415d5dad8ff6014d6a8 ;; \
         *) echo "unsupported TARGETARCH=$TARGETARCH" >&2; exit 1 ;; \
       esac \
    && rpm_url="https://repo.mysql.com/yum/mysql-8.4-community/el/9/${rpm_arch}/mysql-community-client-8.4.10-1.el9.${rpm_arch}.rpm" \
    && curl --fail --location --silent --show-error "$rpm_url" --output /tmp/mysql-community-client.rpm \
    && printf '%s  %s\n' "$rpm_sha" /tmp/mysql-community-client.rpm | sha256sum --check --strict - \
    && cd /tmp \
    && rpm2cpio mysql-community-client.rpm | cpio --extract --make-directories --quiet ./usr/bin/mysqlbinlog \
    && install -o root -g root -m 0755 /tmp/usr/bin/mysqlbinlog /usr/bin/mysqlbinlog \
    && mysql --version \
    && mysqldump --version \
    && mysqlbinlog --version \
    && rpm -qa --qf '%{NAME} %{EPOCHNUM}:%{VERSION}-%{RELEASE}.%{ARCH}\n' > /tmp/copper-rpms.after \
    && awk '$1 ~ /^mysql/ {print}' /tmp/copper-rpms.after | LC_ALL=C sort > /tmp/copper-mysql.after \
    && test "$(cat /tmp/copper-mysql.before)" = "$(cat /tmp/copper-mysql.after)" \
    && sha256sum --check --strict /tmp/copper-mysql-cli.sha256 \
    && microdnf clean all \
    && rm -rf /var/cache/dnf /tmp/mysql-community-client.rpm /tmp/usr \
      /tmp/copper-rpms.before /tmp/copper-rpms.after /tmp/copper-mysql.before /tmp/copper-mysql.after /tmp/copper-mysql-cli.sha256

COPY --chmod=0755 images/mysql/gosu-setpriv.sh /usr/local/bin/gosu
