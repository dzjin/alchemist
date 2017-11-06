#!/bin/bash

#SBATCH -p debug
#SBATCH -N 10
#SBATCH -t 00:15:00
#SBATCH -e mysparkjob_%j.err
#SBATCH -o mysparkjob_%j.out
#SBATCH -C haswell
#module load collectl
#start-collectl.sh 

module unload darshan
module load hdf5-parallel
export LD_LIBRARY_PATH=$LD_LBRARY_PATH:$PWD/lib
source setup/cori-start-alchemist-2.sh 5

method=SVD
m=100000
n=50000
k=100
partitions=20

spark-submit --verbose\
  --jars /global/cscratch1/sd/gittens/alchemistSHELL/alchemist/lib/fits.jar,/global/cscratch1/sd/gittens/alchemistSHELL/alchemist/lib/jarfitsobj.jar,/global/cscratch1/sd/gittens/alchemistSHELL/alchemist/lib/jarh4obj.jar,/global/cscratch1/sd/gittens/alchemistSHELL/alchemist/lib/jarh5obj.jar,/global/cscratch1/sd/gittens/alchemistSHELL/alchemist/lib/jarhdf-2.11.0.jar,/global/cscratch1/sd/gittens/alchemistSHELL/alchemist/lib/jarhdf5-2.11.0.jar,/global/cscratch1/sd/gittens/alchemistSHELL/alchemist/lib/jarhdfobj.jar,/global/cscratch1/sd/gittens/alchemistSHELL/alchemist/lib/jarnc2obj.jar,/global/cscratch1/sd/gittens/alchemistSHELL/alchemist/lib/jhdfview.jar,/global/cscratch1/sd/gittens/alchemistSHELL/alchemist/lib/netcdfAll-4.6.5.jar\
  --driver-memory 100g\
  --executor-memory 100g\
  --executor-cores 32 \
  --driver-cores 32  \
  --num-executors 4 \
  --conf spark.driver.extraLibraryPath=$SCRATCH/alchemistSHELL/alchemist/lib\
  --conf spark.executor.extraLibraryPath=$SCRATCH/alchemistSHELL/alchemist/lib\
  --conf spark.eventLog.enabled=true\
  --conf spark.eventLog.dir=$SCRATCH/spark/event_logs\
  --class amplab.alchemist.BasicSuite\
  test/target/scala-2.11/alchemist-tests-assembly-0.0.2.jar $method $m $n $k $partitions 2>&1 | tee test.log

stop-all.sh
exit
#stop-collectl.sh
