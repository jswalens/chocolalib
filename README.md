# Chocola – composable concurrency

Chocola is a Clojure library for concurrent and parallel programming. It provides futures, transactions, and actors. It is unique in ensuring that these three models work correctly even when they are combined.

More at http://soft.vub.ac.be/~jswalens/chocola

## Getting started

Chocola can easily be used in existing Clojure projects that use Leiningen. In your project's `project.clj`, add the following line:

```clj
  :injections [(require 'chocola.core)]
```

This injects the call to include Chocola in your code. Chocola will patch Clojure and modify its internals to use Chocola's semantics.

## To do

This library version of Chocola is still a work in progress. Some things we still plan to do:

* [ ] Add more documentation on how to include Chocola. Check whether everything works as expected.
* [ ] Add documentation on how to use Chocola, what its semantics are...
* [ ] Finish a large number of loose ends in the implementation.

## Publications

We published about Chocola at the following academic conferences:

* [Transactional Tasks: Parallelism in Software Transactions][ecoop] (ECOOP, July 2016)
* [Transactional Actors: Communication in Transactions][seps] (SEPS at SPLASH, October 2017)
* [Chocola: Integrating Futures, Actors, and Transactions][agere] (AGERE at SPLASH, November 2018)

## License

Copyright © 2018–2019 Janwillem Swalens, Software Languages Lab, Vrije Universiteit Brussel. Distributed under the Eclipse Public License, included in the file `LICENSE`.



[ecoop]: http://soft.vub.ac.be/~jswalens/ecoop2016.pdf
[seps]: http://soft.vub.ac.be/~jswalens/seps2017.pdf
[agere]: http://soft.vub.ac.be/~jswalens/agere2018.pdf