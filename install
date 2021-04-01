#!/usr/bin/env bash

set -euo pipefail

version=""
default_install_dir="/usr/local/bin"
install_dir=$default_install_dir
default_download_dir="/tmp"
download_dir=$default_download_dir

print_help() {
    echo "Installs latest (or specific) version of babashka. Installation directory defaults to /usr/local/bin."
    echo -e
    echo "Usage:"
    echo "install [--dir <dir>] [--download-dir <download-dir>] [--version <version>]"
    echo -e
    echo "Defaults:"
    echo " * Installation directory: ${default_install_dir}"
    echo " * Download directory: ${default_download_dir}"
    echo " * Version: <Latest release on github>"
    exit 1
}

if [[ $# -eq 1 ]]; then
   install_dir=${1:-}
fi

while [[ $# -gt 1 ]]
do
    key="$1"
    if [[ -z "${2:-}" ]]; then
        print_help
    fi

    case $key in
        --dir)
            install_dir="$2"
            shift
            shift
            ;;
        --download-dir)
            download_dir="$2"
            shift
            shift
            ;;
        --version)
            version="$2"
            shift
            shift
            ;;
        *)    # unknown option
            print_help
            shift
            ;;
    esac
done

if [[ "$version" == "" ]]; then
  version="$(curl -sL https://raw.githubusercontent.com/babashka/babashka/master/resources/BABASHKA_RELEASED_VERSION)"
fi

case "$(uname -s)" in
    Linux*)     platform=linux;;
    Darwin*)    platform=macos;;
esac

case "$(uname -m)" in
    aarch64)   arch=aarch64;;
esac
arch=${arch:-amd64}

# Ugly ugly conversion of version to a comparable number
IFS='.' read -ra VER <<< "$version"
vernum=$(printf "%03d%03d%03d" "${VER[0]}" "${VER[1]}" "${VER[2]}")

if [[ $vernum -le 000002013 ]]; then
  ext="zip"
  util="$(which unzip) -qqo"
else
  ext="tar.gz"
  util="$(which tar) -zxf"
fi

download_url="https://github.com/babashka/babashka/releases/download/v$version/babashka-$version-$platform-$arch."$ext

mkdir -p "$download_dir"
cd "$download_dir"
echo -e "Downloading $download_url to $download_dir"
rm -rf "babashka-$version-$platform-$arch."$ext
rm -rf "bb"
curl -o "babashka-$version-$platform-$arch."$ext -sL $download_url
$util "babashka-$version-$platform-$arch."$ext
rm "babashka-$version-$platform-$arch."$ext

if [ "$download_dir" != "$install_dir" ]
then
    mkdir -p "$install_dir"
    if [ -f "$install_dir/bb" ]; then
        echo "Moving $install_dir/bb to $install_dir/bb.old"
    fi
    mv -f "$download_dir/bb" "$install_dir/bb"
fi

echo "Successfully installed bb in $install_dir"
