#!/usr/bin/env bash
# Build APK lewat GitHub Actions untuk tool build_apk (MYTHOS): git push, tunggu workflow, unduh APK.
# Pemakaian: bash build-github.sh [folder-proyek]
# Autentikasi memakai token GitHub dari Settings melalui GH_TOKEN; pengguna tidak perlu menjalankan login manual.
# Penanda di baris-baris terakhir keluaran: KITA_BUILD=ok|gagal, KITA_APK=<path>.
set -u
fail() { echo "[build_apk] GAGAL: $*"; echo "KITA_BUILD=gagal"; exit 1; }
say() { echo "[build_apk] $*"; }

cd "${1:-.}" 2>/dev/null || fail "folder proyek tidak ditemukan: ${1:-.}"
MYTHOS_GH_OWNER="${2:-${MYTHOS_GH_OWNER:-}}"
MYTHOS_GH_REPO="${3:-${MYTHOS_GH_REPO:-}}"
MYTHOS_GH_PRIVATE="${4:-${MYTHOS_GH_PRIVATE:-true}}"
MYTHOS_GH_TOKEN="${5:-${MYTHOS_GH_TOKEN:-}}"
if [ -n "$MYTHOS_GH_TOKEN" ]; then export GH_TOKEN="$MYTHOS_GH_TOKEN"; fi
for t in git gh; do
  command -v "$t" >/dev/null 2>&1 && continue
  say "Memasang $t..."
  if [ -n "${PREFIX:-}" ] && command -v pkg >/dev/null 2>&1; then pkg install -y "$t" >/dev/null 2>&1
  else apt-get install -y --no-install-recommends "$t" >/dev/null 2>&1; fi
  command -v "$t" >/dev/null 2>&1 || fail "tidak bisa memasang $t"
done
# Jelaskan galat gh/GitHub (401/403) dalam bahasa yang bisa ditindaklanjuti. Token tidak pernah dicetak.
explain_gh_error() {
  case "$1" in
    *"HTTP 401"*|*"Bad credentials"*) echo "GitHub menolak token (HTTP 401): token salah, dicabut, atau kedaluwarsa. Buat token baru lalu isi di Pengaturan." ;;
    *[Rr]ate*limit*) echo "GitHub membatasi laju API (HTTP 403 rate limit): tunggu beberapa menit lalu panggil build_apk lagi." ;;
    *SAML*|*saml*) echo "GitHub menolak token (HTTP 403): organisasi memakai SAML SSO. Otorisasi token ke organisasi di pengaturan token GitHub." ;;
    *"HTTP 403"*|*"Resource not accessible"*) echo "GitHub menolak izin token (HTTP 403): token valid tetapi izinnya kurang. Butuh scope repo dan workflow (token klasik), atau akses Contents, Actions, dan Workflows baca/tulis ke repo ini (fine-grained)." ;;
  esac
}
if [ -n "$MYTHOS_GH_TOKEN" ]; then
  AUTH_OUT="$(gh api -i user 2>&1)"
  AUTH_CODE="$(printf '%s\n' "$AUTH_OUT" | sed -n '1s/^HTTP\/[0-9.]* \([0-9][0-9]*\).*/\1/p')"
  if [ "$AUTH_CODE" != 200 ]; then
    AUTH_WHY="$(explain_gh_error "$AUTH_OUT")"
    AUTH_SCOPES="$(printf '%s\n' "$AUTH_OUT" | grep -i '^x-oauth-scopes:' | head -n 1 | cut -d: -f2- | tr -d '\r ')"
    if [ -z "$AUTH_CODE" ]; then AUTH_WHY="tidak bisa menghubungi api.github.com (cek koneksi internet)"; fi
    [ -n "$AUTH_WHY" ] || AUTH_WHY="GitHub menjawab HTTP ${AUTH_CODE} untuk token dari Pengaturan"
    [ -z "$AUTH_SCOPES" ] || AUTH_WHY="$AUTH_WHY (scope token saat ini: $AUTH_SCOPES)"
    fail "$AUTH_WHY"
  fi
else
  gh auth status >/dev/null 2>&1 || fail "Token GitHub belum diisi di Settings"
fi

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || git init -q || fail "git init gagal"
[ -f .gitignore ] || printf '%s\n' build/ .gradle/ local.properties '*.apk' keystore.properties '*.jks' '*.keystore' '.kita-*' > .gitignore
git add -A
git reset -q -- keystore.properties '*.jks' '*.keystore' local.properties .kita-build.sh >/dev/null 2>&1 || true
git commit -qm "build dari agen" >/dev/null 2>&1 || true
gh auth setup-git >/dev/null 2>&1 || true
if git remote get-url origin >/dev/null 2>&1; then
  git push -q 2>&1 || fail "git push gagal (cek koneksi dan izin tulis ke repo)"
else
  [ -n "${MYTHOS_GH_OWNER:-}" ] && [ -n "${MYTHOS_GH_REPO:-}" ] || fail "Link repository GitHub belum diisi di Pengaturan"
  if gh repo view "$MYTHOS_GH_OWNER/$MYTHOS_GH_REPO" >/dev/null 2>&1; then
    git remote add origin "https://github.com/$MYTHOS_GH_OWNER/$MYTHOS_GH_REPO.git"
    git push -q -u origin HEAD 2>&1 || fail "git push ke $MYTHOS_GH_OWNER/$MYTHOS_GH_REPO gagal (cek izin token; repo yang sudah berisi proyek lain juga bisa menolak)"
  else
  VIS="${MYTHOS_GH_PRIVATE:-true}"
  if [ "$VIS" = "true" ]; then VIS_FLAG="--private"; else VIS_FLAG="--public"; fi
  ERR="$(mktemp)"
  if ! gh repo create "$MYTHOS_GH_OWNER/$MYTHOS_GH_REPO" $VIS_FLAG --source . --remote origin --push > /dev/null 2>"$ERR"; then
    WHY="$(tr '\n' ' ' < "$ERR" | sed -E 's/(ghp_|github_pat_)[A-Za-z0-9_]+/[token disembunyikan]/g' | cut -c1-300)"
    rm -f "$ERR"
    fail "gagal membuat repository GitHub baru: $WHY"
  fi
  rm -f "$ERR"
  fi
fi
SHA="$(git rev-parse HEAD)"
say "terkirim: ${SHA:0:7}; menunggu workflow GitHub Actions..."

ID=""; LISTERR=""
for _ in $(seq 1 24); do
  R="$(gh run list --commit "$SHA" --limit 1 --json databaseId --jq '.[0].databaseId' 2>&1)"
  case "$R" in
    ''|*[!0-9]*) [ -z "$R" ] || LISTERR="$R" ;;
    *) ID="$R" ;;
  esac
  [ -n "$ID" ] && break
  # 401/403 tidak akan sembuh dengan menunggu: berhenti dan jelaskan penyebabnya.
  [ -z "$(explain_gh_error "$LISTERR")" ] || fail "$(explain_gh_error "$LISTERR")"
  sleep 5
done
[ -n "$ID" ] || fail "workflow tidak muncul. Cek .github/workflows/build-apk.yml dan tab Actions di GitHub"

# Ambil log langkah yang gagal. Koneksi HP bisa putus sesaat: coba ulang 3 kali, lalu cadangan
# (nama langkah yang gagal + tautan), supaya penyebab kegagalan tidak hilang.
faillog() {
  local n="$1" t out
  for t in 1 2 3; do
    if out="$(gh run view "$ID" --log-failed 2>&1)" && [ -n "$out" ]; then printf '%s\n' "$out" | tail -n "$n"; return 0; fi
    sleep 3
  done
  say "Log lengkap tidak bisa diambil (koneksi putus?). Langkah yang gagal:"
  gh run view "$ID" --json jobs --jq '.jobs[].steps[] | select(.conclusion=="failure") | "- " + .name' 2>&1 | head -n 10
  say "Buka di browser: $(gh run view "$ID" --json url --jq .url 2>/dev/null)"
}

BAD=0
while :; do
  if S="$(gh run view "$ID" --json status,conclusion --jq '.status + " " + .conclusion' 2>&1)"; then BAD=0
  else
    BAD=$((BAD+1))
    [ -z "$(explain_gh_error "$S")" ] || fail "$(explain_gh_error "$S")"
    [ "$BAD" -lt 6 ] || fail "tidak bisa membaca status workflow berulang kali (cek koneksi internet)"
    S=""
  fi
  say "run $ID: ${S:-tidak diketahui}"
  case "$S" in completed*) break ;; esac
  sleep 20
done

OUT=app/build/outputs/apk/debug
rm -rf "$OUT"; mkdir -p "$OUT"
GOT=0
DLERR=""
for _ in 1 2 3; do
  if DLERR="$(gh run download "$ID" -n mythos-debug -D "$OUT" 2>&1)" && ls "$OUT"/*.apk >/dev/null 2>&1; then GOT=1; break; fi
  sleep 3
done
if [ "$GOT" = 1 ]; then
  case "$S" in *success) ;; *) say "APK ada, tetapi workflow berakhir '$S' (kemungkinan tes unit gagal). Log langkah yang gagal:"; faillog 40 ;; esac
  echo "KITA_APK=$(pwd)/$(ls "$OUT"/*.apk | head -n 1)"
  echo "KITA_BUILD=ok"
else
  DLWHY="$(explain_gh_error "$DLERR")"
  [ -z "$DLWHY" ] || say "$DLWHY"
  say "Log langkah yang gagal:"
  faillog 60
  fail "APK tidak dihasilkan"
fi
