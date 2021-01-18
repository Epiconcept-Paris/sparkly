SHELL = bash
export OPENBLAS_NUM_THREADS=1
export SPARK_VERSION=3.0.1

compile:
	sbt compile
	
package:
	sbt package
	
publish:
	sbt publishSigned
 
