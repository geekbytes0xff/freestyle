/*
 * Copyright 2017 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package freestyle

import cats.implicits._
import cats.Monad
import org.scalatest.{Matchers, WordSpec}

class implicitsTests extends WordSpec with Matchers {

  "Implicits" should {

    import algebras._
    import freestyle.implicits._

    "provide a Monad for FreeS" in {
      type G[A] = FreeS[SCtors1.Op, A]
      "Monad[G]" should compile
    }

    "enable traverseU" in {
      implicit val optionHandler = interps.optionHandler1
      val s = SCtors1[SCtors1.Op]
      val program = List(1, 2).traverseU(s.x)
      program.exec[Option] shouldBe (Some(List(1, 2)))
    }

    "enable sequence" in {
      implicit val optionHandler = interps.optionHandler1
      val s = SCtors1[SCtors1.Op]
      val program = List(s.x(1), s.x(2)).sequence[FreeS[SCtors1.Op, ?], Int]
      program.exec[Option] shouldBe (Some(List(1, 2)))
    }
  }
}
