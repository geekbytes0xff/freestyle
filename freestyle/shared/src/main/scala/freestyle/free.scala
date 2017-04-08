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

import scala.reflect.macros.blackbox.Context

trait EffectLike[F[_]] {
  final type OpSeq[A] = FreeS[F, A]
  final type OpPar[A] = FreeS.Par[F, A]
}

object freeImpl {

  def free(c: Context)(annottees: c.Expr[Any]*): c.universe.Tree = {
    import c.universe._
    import c.universe.internal.reificationSupport._

    /* Macro Hygiene: we first declare a long list of fresh names for term and types. */
    val OP = TypeName("Op") // Root trait of the Effect ADT
    val MM = freshTypeName("MM$") // MM Target of the Handler's natural Transformation
    val LL = freshTypeName("LL$") // LL is the target of the Lifter's Injection
    val AA = freshTypeName("AA$") // AA is the parameter inside type applications

    def fail(msg: String) = c.abort(c.enclosingPosition, msg)

    // Messages of error
    val invalid = "Invalid use of the `@free` annotation"
    val abstractOnly = "The `@free` annotation can only be applied to a trait or to an abstract class."
    val noCompanion = "The trait (or class) annotated with `@free` must have no companion object."

    def gen(): Tree = annottees match {
      case Expr(cls: ClassDef) :: Nil =>
        if (cls.mods.hasFlag(Flag.TRAIT | Flag.ABSTRACT)) {
          val effectTrait = cls.duplicate
          q"""
            ${mkEffectTrait(effectTrait.duplicate)}
            ${mkEffectObject(effectTrait.duplicate)}
          """
        } else fail(s"$invalid in ${cls.name}. $abstractOnly")

      case Expr(cls: ClassDef) :: Expr(_) :: _ => fail( s"$invalid in ${cls.name}. $noCompanion")

      case _ => fail( s"$invalid. $abstractOnly")
    }

    def isRequestDef(tree: Tree): Boolean = tree match {
      case q"$mods def $name[..$tparams](...$paramss): OpSeq[..$args]" => true
      case q"$mods def $name[..$tparams](...$paramss): OpPar[..$args]" => true
      case _ => false
    }

    def mkEffectTrait(cls: ClassDef): ClassDef = {
      val FF = freshTypeName("FF$")

      val ffTParam: TypeDef = {
        val wildcard = TypeDef(Modifiers(Flag.PARAM), typeNames.WILDCARD, List(), TypeBoundsTree(EmptyTree, EmptyTree))
        TypeDef(Modifiers(Flag.PARAM), FF, List(wildcard), TypeBoundsTree(EmptyTree, EmptyTree))
      }
      val ClassDef(mods, name, tparams, Template(parents, self, body)) = cls

      val ntparams = ffTParam :: tparams
      val nparents = parents :+ tq"freestyle.EffectLike[$FF]"

      ClassDef(mods, name, ntparams, Template(nparents, self, body))
    }

    class Request(reqDef: DefDef) {

      import reqDef.tparams

      val reqImpl = TermName(reqDef.name.toTermName.encodedName.toString)

      // Name of the Request ADT Class
      private[this] val Req: TypeName = TypeName(reqDef.name.toTypeName.encodedName.toString.capitalize + "OP")
      private[this] val Res = reqDef.tpt.asInstanceOf[AppliedTypeTree].args.last
      private[this] val ReqC = Req.toTermName

      val params: List[ValDef] = reqDef.vparamss.flatten

      def handlerCase: CaseDef  =
        if (params.isEmpty)
          cq"$ReqC() => $reqImpl"
        else {
          // filter: !v.mods.hasFlag(Flag.IMPLICIT)
          val ffs = params.map( v => q"l.${v.name}")
          val uss = params.map( v => pq"_")
          cq"l @ $ReqC(..$uss) => $reqImpl(..$ffs)"
        }

      def handlerDef: DefDef =
        if (params.isEmpty)
          q"protected[this] def $reqImpl[..$tparams]: $MM[$Res]"
        else
          q"protected[this] def $reqImpl[..$tparams](..$params): $MM[$Res]"


      def mkRequestClass(effTTs: List[TypeDef]): ClassDef = {
        // Note: Effect trait type params are added to the request case class because its args may contain them
        val TTs = effTTs ++ tparams
        if (params.isEmpty)
          q"case class $Req[..$TTs]() extends $OP[$Res]"
        else
          q"case class $Req[..$TTs](..$params) extends $OP[$Res]"
      }

      def raiser: DefDef = {
        val injected = {
          /*filter: if !v.mods.hasFlag(Flag.IMPLICIT)*/
          val args = params.map(_.name)
          q"FreeS.inject[$OP, $LL]( $ReqC[..${tparams.map(_.name)} ](..$args) )"
        }

        val tpt = reqDef.tpt.asInstanceOf[AppliedTypeTree]
        val (liftType, impl) = tpt match {
          case tq"OpSeq[$aa]" => (tpt, q"FreeS.liftPar($injected)" )
          case tq"OpPar[$aa]" => (tpt, injected )
          case _ => // Note: due to filter in getRequestDefs, this case is unreachable.
            fail(s"unknown abstract type found in @free container: $tpt : raw: ${showRaw(tpt)}")
        }

        q"override def ${reqDef.name}[..$tparams](...${reqDef.vparamss}): $liftType = $impl"
      }
    }

    def mkEffectObject(effectTrait: ClassDef): ModuleDef = {

      val requests: List[Request] = effectTrait.impl.collect {
        case dd:DefDef if isRequestDef(dd) => new Request(dd.asInstanceOf[DefDef])
      }

      val Eff = effectTrait.name
      val TTs = effectTrait.tparams
      val tns = TTs.map(_.name)
      val ev =  freshTermName("ev$")
      val ii =  freshTermName("ii$")
      val fa =  freshTermName("fa$")

      q"""
        object ${Eff.toTermName} {

          import _root_.cats.arrow.FunctionK
          import _root_.cats.free.Inject
          import _root_.freestyle.FreeS

          sealed trait $OP[$AA] extends scala.Product with java.io.Serializable
          ..${requests.map( _.mkRequestClass(TTs))}

          trait Handler[$MM[_], ..$TTs] extends FunctionK[$OP, $MM] {
            ..${requests.map( _.handlerDef )}

            override def apply[$AA]($fa: $OP[$AA]): $MM[$AA] = $fa match {
              case ..${requests.map(_.handlerCase )}
            }
          }

          class To[$LL[_], ..$TTs](implicit $ii: Inject[$OP, $LL]) extends $Eff[$LL, ..$tns] {
              ..${requests.map(_.raiser )}
          }

          implicit def to[$LL[_], ..$TTs](implicit $ii: Inject[$OP, $LL]):
              To[$LL, ..$tns] = new To[$LL, ..$TTs]

          def apply[$LL[_], ..$TTs](implicit $ev: $Eff[$LL, ..$tns]): $Eff[$LL, ..$tns] = $ev

        }
      """
    }

    gen()
  }
}
