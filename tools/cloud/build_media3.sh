#!/bin/bash
set -e
TC=/home/claude/tc
AJ=$TC/Reginer_aosp-android-jar/android-36/android.jar
LS=$TC/bt/build-tools/34.0.4/core-lambda-stubs.jar
OUT=$TC/build-m3
D8JAR=$TC/bt/build-tools/34.0.4/lib/d8.jar
rm -rf $OUT && mkdir -p $OUT/{annos,unsafe,guava,media3,src-patch}
JC="javac -J-Xmx3g -nowarn -Xlint:none -encoding UTF-8 -source 8 -target 8"

echo "[1] compile-only annotations"
find $TC/stubs/src -name "*.java" -not -path "*/sun/misc/*" > $OUT/annos.txt
find $TC/deps/errorprone/annotations/src/main/java $TC/deps/checker/checker-qual/src/main/java $TC/deps/j2objc/annotations/src/main/java -name "*.java" -not -name "module-info.java" >> $OUT/annos.txt
$JC -d $OUT/annos @$OUT/annos.txt 2>&1 | grep -v "^warning\|^Note\|^[0-9]* warning" || true

echo "[2] sun.misc stub"
$JC -bootclasspath $AJ:$LS -d $OUT/unsafe $TC/stubs/src/sun/misc/Unsafe.java 2>&1 | grep -v "^warning\|^Note\|^[0-9]* warning" || true

echo "[3] guava"
find $TC/deps/guava/android/guava/src $TC/deps/guava/futures/failureaccess/src -name "*.java" -not -name "module-info.java" > $OUT/guava.txt
wc -l < $OUT/guava.txt
$JC -bootclasspath $AJ:$LS -cp $OUT/annos:$OUT/unsafe -d $OUT/guava @$OUT/guava.txt 2>&1 | grep -v "^warning\|^Note\|^[0-9]* warning" | head -40 || true

echo "[4] media3"
M3=$TC/media3/libraries
SRCS=""
for m in common container database datasource decoder extractor exoplayer exoplayer_hls exoplayer_dash exoplayer_rtsp; do SRCS="$SRCS $M3/$m/src/main/java"; done
# patch: androidx ExifInterface -> framework ExifInterface
P=$OUT/src-patch/androidx/media3/datasource; mkdir -p $P
sed -e 's/import androidx.exifinterface.media.ExifInterface;/import android.media.ExifInterface;/' \
    -e 's/int rotationDegrees = exifInterface.getRotationDegrees();/int rotationDegrees = exifRotation(exifInterface);/' \
    $M3/datasource/src/main/java/androidx/media3/datasource/BitmapUtil.java > $P/BitmapUtil.java
python3 - "$P/BitmapUtil.java" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
helper='''
  private static int exifRotation(ExifInterface exif) {
    switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
      case ExifInterface.ORIENTATION_ROTATE_90: case ExifInterface.ORIENTATION_TRANSPOSE: return 90;
      case ExifInterface.ORIENTATION_ROTATE_180: case ExifInterface.ORIENTATION_FLIP_VERTICAL: return 180;
      case ExifInterface.ORIENTATION_ROTATE_270: case ExifInterface.ORIENTATION_TRANSVERSE: return 270;
      default: return 0;
    }
  }
}
'''
i=s.rstrip().rfind('}')
s=s[:i]+helper
open(p,'w').write(s)
PY
find $SRCS -name "*.java" -not -name "module-info.java" \
  -not -path "*/androidx/media3/datasource/BitmapUtil.java" \
  -not -name "DownloadNotificationHelper.java" -not -name "HlsInterstitialsAdsLoader.java" -not -name "AssetListParser.java" > $OUT/media3.txt
echo $P/BitmapUtil.java >> $OUT/media3.txt
wc -l < $OUT/media3.txt
$JC -bootclasspath $AJ:$LS -cp $OUT/annos:$OUT/unsafe:$OUT/guava -d $OUT/media3 @$OUT/media3.txt 2>&1 | grep -v "^warning\|^Note\|^[0-9]* warning" | head -60 || true
echo "classes: guava=$(find $OUT/guava -name '*.class' | wc -l) media3=$(find $OUT/media3 -name '*.class' | wc -l)"
