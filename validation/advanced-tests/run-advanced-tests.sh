#!/bin/sh -f

# this test needs rewritten, with more useful data (not KPP),
# meanwhile not worth maintaining ...
#exit 0

# coatjava must already be built at ../../coatjava/

# set up environment
CLARA_HOME=$PWD/clara_installation/ ; export CLARA_HOME
COAT=$CLARA_HOME/plugins/clas12/
classPath="$COAT/lib/services/*:$COAT/lib/clas/*:$COAT/lib/utils/*:../lib/*:src/"

# install clara
../../install-clara -c ../../coatjava $CLARA_HOME
if [ $? != 0 ] ; then echo "clara installation error" ; exit 1 ; fi

# download test files
wget --no-check-certificate http://clasweb.jlab.org/clas12offline/distribution/coatjava/validation_files/twoTrackEvents_809_raw.evio.tar.gz

if [ $? != 0 ] ; then echo "wget validation files failure" ; exit 1 ; fi
tar -zxvf twoTrackEvents_809_raw.evio.tar.gz

export JAVA_OPTS="-Djava.util.logging.config.file=$PWD/../../etc/logging/debug.properties"

# run decoder
$COAT/bin/decoder -t -0.5 -s 0.0 -i ./twoTrackEvents_809_raw.evio -o ./twoTrackEvents_809.hipo -c 2

# run reconstruction with clara
echo "set inputDir $PWD/" > cook.clara
echo "set outputDir $PWD/" >> cook.clara
echo "set threads 1" >> cook.clara
echo "set javaOptions \"-Xmx2g -Djava.util.logging.config.file=$PWD/../../etc/logging/debug.properties\"" >> cook.clara
echo "set session s_cook" >> cook.clara
echo "set description d_cook" >> cook.clara
ls twoTrackEvents_809.hipo > files.list
echo "set fileList $PWD/files.list" >> cook.clara
echo "set servicesFile $COAT/etc/services/kpp.yaml" >> cook.clara
echo "run local" >> cook.clara
echo "exit" >> cook.clara
$CLARA_HOME/bin/clara-shell cook.clara
#if [ $? != 0 ] ; then echo "reconstruction with clara failure" ; exit 1 ; fi

# compile test codes
javac -cp $classPath src/kpptracking/KppTrackingTest.java 
if [ $? != 0 ] ; then echo "KppTrackingTest compilation failure" ; exit 1 ; fi

# run KppTracking junit tests
java -DCLAS12DIR="$COAT" -Xmx1536m -Xms1024m -cp $classPath org.junit.runner.JUnitCore kpptracking.KppTrackingTest
if [ $? != 0 ] ; then echo "KppTracking unit test failure" ; exit 1 ; else echo "KppTracking passed unit tests" ; fi
