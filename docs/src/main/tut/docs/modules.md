---
layout: docs
title: Modules
---

# Modules

Freestyle `@module` serves the purpose of logically combining related Algebras that are frequently used together.
In the same way architectures are built traditionally in layers where separations of concerns is paramount Modules
help organizing algebras in groups that can be arbitrarily nested.

To illustrate how Modules work let's define a few algebras first.
We will start with some basic low level style ops related to persistence.
In our Persistence related algebras we have some ops that can go against a DB and others to a Cache service or system.
In the presentation side an application may display or perform some input validation.

```tut:silent
import io.freestyle._

@free trait Database[F[_]] {
	def get(id: Int): FreeS[F, Int]
}
@free trait Cache[F[_]] {
  def get(id: Int): FreeS[F, Option[Int]]
}
@free trait Presenter[F[_]] {
	def show(id: Int): FreeS[F, Int]
}
@free trait IdValidation[F[_]] {
	def validate(id: Option[Int]): FreeS[F, Int]
}
```

At this point we can group these different application concerns in modules like so

```tut:silent
@module trait Persistence[F[_]] {
	val database: Database[F]
	val cache: Cache[F]
}
@module trait Display[F[_]] {
	val presenter: Presenter[F]
	val validator: IdValidation[F]
}
```

This enables to build programs that are properly typed and parameterized in a modular and composable way.

```tut:silent
def program[F[_]](
	implicit
	  display: Display[F],
	  persistence: Persistence[F]): FreeS[F, Int] = {
  import display._, persistence._
  for {
    cachedToken <- cache.get(1)
    id <- validator.validate(cachedToken)
    value <- database.get(id)
    view <- presenter.show(value)
  } yield view
}
```

Modules can be further nested so they become part of the tree that conforms an application or library.

```tut:silent
@module trait App[F[_]] {
  val persistence: Persistence[F]
  val display: Display[F]
}
```

The `@module` annotation works in a similar way as the `@free` one by generating all the boilerplate
and implicit machinery necessary to assemble apps and libraries based on `Free`.

## Automatic method implementations

From the abstract sub modules references Freestyle creates implementations based on the implicit evidences of the members it finds declared.
In the case above when `Application` is expanded a companion object will be generated which includes a class a definition
implementing `Application[F[_]]` where both `persistence` and `display` are based on the implicits evidences generated by `@free` and other `@module`
annotations.

## Dependency Injection

Freestyle automatically generated the most common implicit machinery def's found in most Scala application to expose instances through companion evidences.
You don't need to provide implicit evidences for it's dependencies in Freestyle because those were already expressed inside their respective companions.
Scala uses implicit instances in companion objects if it finds those after [traversing scopes at the call site]().

This gives the caller an opportunity to override at any point the instances generated automatically by Freestyle with explicit ones.

Freestyle also creates an implicit method which requires implicitly all the module dependencies and a convenient `apply` method which allows obtaining
Module instances in an easy way. As in `@free` this effectively enables implicits based Dependency Injection where you may choose to override implementations
using the implicits scoping rules to place different implementations where appropriate.
This also solves all Dependency Injection problems automatically for all modules in applications that model their layers a modules with the `@module` annotation.

```scala
val app = App[App.T]
```

```tut:silent
def doWithApp[F[_]](implicit app: App[F]) = ???
```

## Convenient type aliases

All companions generated with `@module` contain a convenient type alias `T` that you can refer to and that points to the root ADT nodes of the most inner `@free` leaves.
Freestyle recursively walks the Module dependencies until it finds the deepest modules that contain `@free` smart constructors generating a properly aligned `Coproduct`
for all it's contained algebras. This allows contained algebras to be composed.

If you were to create this by hand in the case of the example above it will look like this:

```tut:silent
import cats.data.Coproduct

type C01[A] = Coproduct[Database.T, Cache.T, A]
type C02[A] = Coproduct[Presenter.T, C01, A]
type ManualAppCoproduct[A] = Coproduct[IdValidation.T, C02, A]
```

Things get more complicated once the number of Algebras grows.
Fortunately Freestyle automatically aligns all those for you and gives you an already aligned `Coproduct` of all algebras
contained by a Module whether directly referenced or transitively through it's modules dependencies.

```tut:silent
implicitly[App.T[_] =:= ManualAppCoproduct[_]]
```

We've covered so far how Freestyle can help in building and composing module programs based on `Free`, but `Free` programs are
useless without a runtime interpreter that can evaluate the `Free` structure.

Next we will show you how `Freestyle` helps you simplify the way you define [runtime interpreters](interpreters.html) for `Free` applications.