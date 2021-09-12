![Chocola](http://soft.vub.ac.be/~jswalens/chocola/cookie-100.png)

# Chocola – composable concurrency

Chocola is a Clojure library for concurrent and parallel programming. It provides futures, transactions, and actors. It is unique in ensuring that these three models work correctly even when they are combined.

More at https://chocola.jnwllm.be/

## Getting started

Because this library monkey-patches Clojure, it is a bit complicated to get working: we need to make sure it is loaded before Clojure, i.e. the Chocola jar must appear earlier in the classpath than Clojure. Assuming you use Leiningen, you can follow these steps:

1. In a temporary folder, checkout this repository and create it:
```sh
$ git clone https://github.com/jswalens/chocolalib.git
$ lein uberjar
```
This creates the file `target/chocola-2.0.0-standalone.jar`, which contains Clojure 1.8.0, Chocola, and its dependencies (core.match).

2. Copy this file into the `resources` folder of your project. (The folder can have another name too.)

3. Update your project's `project.clj` to add the following lines:
```clj
  :resource-paths ["resources/chocola-2.0.0-standalone.jar"]
  :injections [(require 'chocola.core)]
```
The first line will make sure that Chocola and its dependencies are loaded. The second line injects a call to include Chocola in your code, which will patch Clojure and modify its internals to use Chocola's semantics.

Some code examples of Chocola can be found at http://soft.vub.ac.be/~jswalens/chocola.

## Publications

We published about (the ideas behind) Chocola in the following academic papers:

* [Chocola: Composable Concurrency Language][toplas] (TOPLAS, January 2021, [also available here][toplas2])
* [Chocola: Integrating Futures, Actors, and Transactions][agere] (AGERE at SPLASH, November 2018)
* [Transactional Tasks: Parallelism in Software Transactions][ecoop] (ECOOP, July 2016)
* [Transactional Actors: Communication in Transactions][seps] (SEPS at SPLASH, October 2017)

You can find a lot more information in [my PhD thesis](http://soft.vub.ac.be/~jswalens/phd2018.pdf).

## To do

* Documentation:
    * [ ] Check whether getting started always works as expected
    * [ ] Add documentation on how to use Chocola and explain its semantics
* Implementation:
    * [ ] Garbage collection of unreachable actors (currently, unreachable actors will stay idle forever)
    * [ ] Interaction with exceptions/errors (seems to mostly work correctly, but exhaustive testing is needed)
    * [ ] `commute` (doesn't work as expected in transactional futures)
    * [ ] Performance improvements (see TODOs throughout code)

## License

Copyright © 2018–2019 Janwillem Swalens, Software Languages Lab, Vrije Universiteit Brussel. Distributed under the Eclipse Public License, included in the file `LICENSE`.



[toplas]: https://dl.acm.org/doi/10.1145/3427201
[toplas2]: https://jnwllm.be/publications/toplas2021.pdf
[ecoop]: http://soft.vub.ac.be/~jswalens/ecoop2016.pdf
[seps]: http://soft.vub.ac.be/~jswalens/seps2017.pdf
[agere]: http://soft.vub.ac.be/~jswalens/agere2018.pdf
[phd]: http://soft.vub.ac.be/~jswalens/phd2018.pdf