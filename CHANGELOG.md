# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]
### Changed
- bar

### Fixed
- foo

## 0.6.4 - 2018-09-13
- fix bug that made rotation still throw on ES6.x
- hopefully last embarassing attempt at fixing this bug

## 0.6.3 - 2018-09-12
- fix bug that made rotation still throw on ES6.x

## 0.6.2 - 2018-09-11
- fix bug that made rotation/purge throw on ES6.x

## 0.6.1 - 2018-08-23
- bump spandex dep to 0.6.4
- fix some typos in README (thanks boernd and brutasse)
- improve error handling

## 0.4.1 - 2017-07-11
- move from clojurewerkz/elastisch to qbits/spandex

## 0.3.6 - 2017-06-06
- adds elasticsearch 5.x compatibility
- bump dep versions to match riemann 0.2.13

## 0.3.5 - 2017-01-13

### Fixed
- low frequency (lower than step) events were lost

## 0.3.3 - 2016-09-05

### Fixed
- events were lost e.g. not part of aggregation
  this is now fixed in average/minimum/maximum/counter

## 0.3.2 - 2016-03-31

### Fixed
- purge is now fixed (was noop)

## 0.3.1 - 2016-03-22

### Changed
- remove at-at dependency
- change rotate/purge semantics

## 0.2.1 - 2016-03-17

### Changed
- change semantics of higher-level functions
- implementes alias rotation

## [0.1.1] - 2016-02-05
- initial release

