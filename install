#!/usr/bin/env bash

set -euo pipefail

print_help() {
    echo "Installs latest version of babashka. Installation directory defaults to /usr/local/bin."
    echo -e
    echo "Usage:"
    echo "installer.sh [<dir>]"
    exit 1
}

default_install_dir="/usr/local/bin"
install_dir=$default_install_dir
install_dir_opt=${1:-}
if [ "$install_dir_opt" ]; then
    install_dir="$install_dir_opt"
fi

download_dir=/tmp

latest_release="$(curl -sL https://raw.githubusercontent.com/borkdude/babashka/master/resources/BABASHKA_RELEASED_VERSION)"

case "$(uname -s)" in
    Linux*)     platform=linux;;
    Darwin*)    platform=macos;;
esac

download_url="https://github.com/borkdude/babashka/releases/download/v$latest_release/babashka-$latest_release-$platform-amd64.zip"

cd "$download_dir"
echo -e "Downloading $download_url."
curl -o "babashka-$latest_release-$platform-amd64.zip" -sL "https://github.com/borkdude/babashka/releases/download/v$latest_release/babashka-$latest_release-$platform-amd64.zip"
unzip -qqo "babashka-$latest_release-$platform-amd64.zip"
rm "babashka-$latest_release-$platform-amd64.zip"

cd "$install_dir"
if [ -f babashka ]; then
    echo "Moving $install_dir/bb to $install_dir/bb.old"
fi

mv -f "$download_dir/bb" "$PWD/bb"

echo "Successfully installed bb in $install_dir."
