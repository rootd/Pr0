#!/bin/sh

if [ $# != 1 ] ; then
	echo "usage: $0 icon.svg"
	exit 1
fi

SVG=$1
PNG=$(basename $SVG .svg).png

mkdir -p ../app/src/main/res/drawablhdpi
inkscape -d$((90*240/160)) --export-png=../app/src/main/res/drawable-hdpi/$PNG $SVG
optipng -o7 ../app/src/main/res/drawable-hdpi/$PNG
git add ../app/src/main/res/drawable-hdpi/$PNG

mkdir -p ../app/src/main/res/drawable-xhdpi
inkscape -d$((90*320/160)) --export-png=../app/src/main/res/drawable-xhdpi/$PNG $SVG
optipng -o7 ../app/src/main/res/drawable-xhdpi/$PNG
git add ../app/src/main/res/drawable-xhdpi/$PNG

mkdir -p ../app/src/main/res/drawable-xxhdpi
inkscape -d$((90*480/160)) --export-png=../app/src/main/res/drawable-xxhdpi/$PNG $SVG
optipng -o7 ../app/src/main/res/drawable-xxhdpi/$PNG
git add ../app/src/main/res/drawable-xxhdpi/$PNG

mkdir -p ../app/src/main/res/drawable-xxxhdpi
inkscape -d$((90*640/160)) --export-png=../app/src/main/res/drawable-xxxhdpi/$PNG $SVG
optipng -o7 ../app/src/main/res/drawable-xxxhdpi/$PNG
git add ../app/src/main/res/drawable-xxxhdpi/$PNG
