incremental-ifds / REVISER
===========================

This repository contains REVISER as presented in our paper submitted to OOPSLA 2013. REVISER
is an extended version of the HEROS IDE solver which is able to incrementally update analysis
results.

If you have any questions or concerns, please feel free to use the issue tracker or contact us
at Steven.Arzt@ec-spride.de. 

Running the Benchmarks
------------------------

The simplest way to run the JUnit test cases and benchmarks we conducted for out OOPSLA paper+
is to run the "runDynamicTest.sh" shell script. It creates one output log file and one error
log file for each test case.

The test cases carrying a "_Rerun" suffix first run the solver on the old version of the target
code, then replace the code with the modified version and finally run the solver again. This can
be seen as the base case.

The test cases ending with "_Propagate" first run the initial computation on the old version of
the target code, then replace the code with the modfified version before they incrementally
update the analysis results. These test cases run much faster than the "_Rerun" ones or than
computing the analysis results twice with the old unchanged version of the HEROS solver.

For running the PDFsam tests, please use the "runPdfsam.sh" script.

Note that our test cases are configured to run with a maximum heap size of 35 GB by default.
Depending on your machine configuration, you may have to change the scripts. We recommend giving
the test cases as much memory as possible for not obfuscating the performance results with
unnecessary garbage collector cycles.

Important Version Note
------------------------

Note that REVISER is built on top of Soot 2.5.0 and may not work with newer versions. We plan
to integrate REVISER into the official HEROS branch as soon as possible. Our scripts thus
include the Soot 2.5.0 JAR file which is also part of this repository.

