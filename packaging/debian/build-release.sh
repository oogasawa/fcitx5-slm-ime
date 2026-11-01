#!/bin/sh
# Builds the Debian packages of a release for one distribution, from the committed source (git archive of HEAD).
#
# Usage: packaging/debian/build-release.sh <suffix> <model dir> <output dir>
#   <suffix>     what is put after the Debian version, e.g. ubuntu24.04 or deb13: the packages become
#                <version>~<suffix>, so the builds for two distributions have different names. With an empty
#                suffix the version stays as it is: slm-ime-model, the same on every distribution, is taken from
#                such a build, and <version> satisfies the dependency of fcitx5-slm-ime <version>~<suffix> on it
#   <model dir>  a directory with slm-ime-weights.bin and tokenizer.json (MODEL_DIR of debian/rules)
#   <output dir> where the .deb files are put
#
# Run it on the distribution that the packages are for (for Debian 13, in a debian:13 container), with the build
# dependencies of debian/control installed. The source is not changed: the version with the suffix is written into a
# copy of debian/changelog.
set -e
[ $# -eq 3 ] || { echo "usage: $0 <suffix> <model dir> <output dir>" >&2; exit 2; }
suffix=$1
model=$(cd "$2" && pwd)
mkdir -p "$3"
out=$(cd "$3" && pwd)
repo=$(cd "$(dirname "$0")/../.." && pwd)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git -C "$repo" archive --prefix=src/ HEAD | tar -x -C "$work"
cd "$work/src"

if [ -n "$suffix" ]; then
    version=$(dpkg-parsechangelog -S Version)
    distribution=$(dpkg-parsechangelog -S Distribution)
    {
        echo "fcitx5-slm-ime ($version~$suffix) $distribution; urgency=medium"
        echo
        echo "  * Built for $suffix."
        echo
        echo " -- $(dpkg-parsechangelog -S Maintainer)  $(dpkg-parsechangelog -S Date)"
        echo
        cat debian/changelog
    } > debian/changelog.new
    mv debian/changelog.new debian/changelog
fi

MODEL_DIR="$model" dpkg-buildpackage -b -us -uc
cp ../*.deb "$out"/
ls -l "$out"
