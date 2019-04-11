[![Build Status](https://travis-ci.org/apache/avro.svg?branch=master)](https://travis-ci.org/apache/avro)

# Apache Avro™

Apache Avro™ is a data serialization system.

Learn more about Avro, please visit our website at:

  http://avro.apache.org/

To contribute to Avro, please read:

  https://cwiki.apache.org/confluence/display/AVRO/How+To+Contribute

# About Mercari's fork

## What's this

The repository is a fork of apache/avro that is modified to meet Mercari's needs.

Although this repo is an open source project, but it's not intended for general purpose like original repo. We don't recommended you depend on this package because there're no guarantees of API stability.
And your comments, issue reports and pullreq's are welcome but we don't ensure to accept and response.

## Branches

- `upstream` is patched original `master`
- `upstream-protobuf-18compat` is patched `avro-protobuf` without requirements for avro-1.9.x
