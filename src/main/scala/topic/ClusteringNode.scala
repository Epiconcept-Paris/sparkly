package demy.mllib.topic

import demy.mllib.index.VectorIndex
import demy.util.{log => l}
import demy.mllib.linalg.implicits._
import org.apache.spark.sql.{SparkSession}
import org.apache.spark.ml.linalg.{Vector => MLVector, Vectors}
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap, ListBuffer}
import scala.{Iterator => It}
import java.sql.Timestamp

case class ClusteringNode (
  params:NodeParams
  , points: ArrayBuffer[MLVector]
  , children: ArrayBuffer[Node] = ArrayBuffer[Node]()
) extends Node {
  assert(!this.params.maxTopWords.isEmpty)
  assert(!this.params.classCenters.isEmpty)
  assert(!this.params.childSplitSize.isEmpty)
  val maxTopWords = this.params.maxTopWords.get
  val classCenters = HashMap(this.params.classCenters.get.map{case (classStr, center) => (classStr.toInt, center)}.toSeq :_*)
  val childSplitSize = this.params.childSplitSize.get
  val classCentersMap = classCenters.groupBy{case (cla, center) => center}.mapValues(p => p.map{case (cla, center) => cla}.toSet)

  val pScores = if(this.params.annotations.size == maxTopWords) Array(this.params.annotations.map(a => a.score) :_*) else Array.fill(maxTopWords)(0.0)
  //println("initialise pscores:")
  //println(pScores.mkString("; "))
  val numCenters = this.classCenters.values.toSet.size
  val cError = this.params.cError.getOrElse(Array.fill(numCenters)(0.0))

  var initializing = this.points.size < this.maxTopWords
  lazy val vectorSize = this.points(0).size
  lazy val vZero = Vectors.dense(Array.fill(vectorSize)(0.0))
  lazy val vCenters = ArrayBuffer.fill(maxTopWords)(vZero)
  val pGAP = ArrayBuffer.fill(maxTopWords)(1.0)
  val cHits = ArrayBuffer.fill(numCenters)(0.0)


  def encodeExtras(encoder:EncodedNode) {
    //thiese two are not necessary if params are updated at encode time. Please remove after test
    encoder.serialized += (("pScores",serialize(pScores) ))
    encoder.serialized += (("cError",serialize(cError) ))
    encoder.serialized += (("initializing", serialize(initializing)))
    if(this.points.size > 0) encoder.serialized += (("vCenters",serialize(vCenters )))
    encoder.serialized += (("pGAP",serialize(pGAP )))
    encoder.serialized += (("cHits",serialize(cHits) ))
  }
  def prettyPrintExtras(level:Int = 0, buffer:ArrayBuffer[String]=ArrayBuffer[String](), stopLevel:Int = -1):ArrayBuffer[String] = {
    buffer += (s"${Range(0, level).map(_ => "-").mkString}> classCenters: ${classCenters}\n")
    buffer
  }

  def toTag(id:Int):TagSource = ClusterTagSource(
    id = this.params.tagId.getOrElse(id)
    , operation = TagOperation.create
    , timestamp = Some(new Timestamp(System.currentTimeMillis()))
    , name = Some(this.params.name)
    , color = this.params.color
    , strLinks = Some(this.params.strLinks)
    , maxTopWords = this.params.maxTopWords
    , childSplitSize = this.params.childSplitSize
    , oFilterMode = Some(this.params.filterMode)
    , oFilterValue = Some(this.params.filterValue.toSet)
  )
  def transform(facts:HashMap[Int, HashMap[Int, Int]] // for each document : Map(class, Map(token positions, per class )) all classes assigned by their parents, ClassifierNodes / AnalgyNodes / ClusteringNodes (before this one in hierarchy)
      , scores:HashMap[Int, Double] // For each class that has already evaluated before -> score
      , vectors:Seq[MLVector]
      , tokens:Seq[String]
      , parent:Option[Node]
      , cGenerator:Iterator[Int] // When new children are created -> generator generates new classes for the children
      , fit:Boolean) { // should model / topwords be updated with new documents ?

    //println("\nTRANSFORM ==================")
    val vectorsInScopeCount =  this.links.keysIterator.map(inClass => facts.get(inClass).map(o => o.size).getOrElse(0)).sum // number tokens in scope
    //println("facts:")
    //println(facts)
    //println("tokens:"+tokens.mkString(";"))
    //println("vectorsInScopeCount: "+vectorsInScopeCount)
    // calculate word2vec combinations..
    //println("this.links: "+this.links)
    //println("this.links.keySet: "+this.links.keySet)
    val scoresByClass =
      for(inClass <- this.links.keySet.iterator)
        yield {
          (inClass,
            for((iBase, _) <- facts.get(inClass).map(o => o.iterator).getOrElse(It[(Int, Int)]()))
              yield {
                val scoredPoint =
                  this.score(
                    iVector = iBase
                    , vectors = vectors
                    , vTokens = tokens
                    , inClass = inClass
                    , parent = parent match {case Some(c) => c match {case c:ClusteringNode => Some(c) case _ => None} case _ => None}
                    , cGenerator = cGenerator
                    , fit = fit
                  )
                //println(s"\tinClass $inClass, scoredPoint : $scoredPoint, iVector: $iVector, tokens: ${tokens.mkString(";")}")
                scoredPoint
              }
          )
        }
    //println("scoresByClass: "+scoresByClass)

    val sequenceScore = this.scoreSequence(scoredTokens = scoresByClass.map{case (inClass, scores) => (inClass, scores.flatMap(s => s))}) // sequence = documents
    //println("sequenceScore: "+sequenceScore)
    sequenceScore.map{case ScoredSequence(inClass, outClass, score, scoredVectors) =>
      scores(outClass) = score
      //println(s"  outClass: $outClass; scores($outClass): ${scores(outClass)}")
      for(ScoredVector(iVector, outVectorClass, outVectorScore, iPoint, iCenter) <- scoredVectors ) {
        facts.get(outClass) match {
          case Some(f) => f(iVector) = iVector
          case None => facts(outClass) = HashMap(iVector -> iVector)
        }
        //println(s"    outClass: $outClass, outVectorClass: $outVectorClass, outVectorScore: $outVectorScore, tokens: ${Seq(tokens(iVector)).mkString("; ")}")
        // affects this class outClass to this vector ; all tokens of this sentence have the class outClass
        this.affectPoint(
          vector = vectors(iVector)
          , tokens = Seq(tokens(iVector))
          , vClass = outClass
          , vScore = outVectorScore
          , iPoint = iPoint
          , iCenter = iCenter
          , weight = 1.0 / vectorsInScopeCount
          , asVCenter = None
          , fit = fit
        )
      }
    }.size
  }

  def onInit(vector:MLVector, tokens:Seq[String], inClass:Int) = {
    // adds one topword
    //if(this.params.hits < 10) println(s"${this.children.size} ${this.classCentersMap.size} ${this.initializing} ${this.pScores.sum} ${this.childSplitSize} ${this.points.size} < ${this.maxTopWords} ${this.params.filterValue} ${inClass}")
    if(initializing && this.points.size < this.maxTopWords) {
        this.links(inClass).iterator
         .flatMap(outClass => this.rel.get(outClass).map(o => o.iterator).getOrElse(It[(Int, Int)]()).map(p => (p, outClass)))
         .filter{case((iOut, _), outClass) => this.points(iOut).similarityScore(vector) > 0.999}
         .toSeq.headOption
         .map{case ((iOut, _), outClass) => outClass}
         match {
           case Some(classToFill) => classToFill
           case None =>
             val classToFill = this.links(inClass).map(outClass => (outClass, this.rel.get(outClass).map(_.size).getOrElse(0))).toSeq.sortWith(_._2 < _._2).head._1
             this.sequences += tokens
             this.points += vector
             this.rel.get(classToFill) match {
               case Some(r) =>
                 this.rel(classToFill)(this.sequences.size-1) = this.sequences.size-1
                 this.inRel(classToFill)(this.sequences.size-1 -> (this.sequences.size-1)) = true
               case None =>
                 this.rel(classToFill) = HashMap(this.sequences.size -1 -> (this.sequences.size -1))
                 this.inRel(classToFill) = HashMap((this.sequences.size -1 -> (this.sequences.size -1), true))

             }
             initializing = this.points.size < this.maxTopWords
             classToFill
         }
       //println(s"init pClasses: ${this.pClasses.toSeq}")
    }
      //println(It.range(1, 3).map(t => s"($t) ${this.classPath.filter{case(cat, parents) => parents(t)}.map{case (cat, _) => this.rel.get(cat) match { case Some(values) => values.map{case (iClass, _) => this.tokens(iClass)}.mkString(",") case None => "-" }}} ").mkString("<---->"))
    else
      -1
  }
  // average of how close (1+cos sim/2) of tokens from their topwords
  def clusterScore = if(cHits.sum == 0) 0.0 else 1.0 - It.range(0, this.cError.size).map(i => cError(i)*cHits(i)).sum / cHits.sum
  def clusterBalance = if(cHits.sum == 0) 0.0 else {
    val sum = cHits.sum
    val avg = sum / cHits.size
    val excedent = It.range(0, this.cHits.size).map(i => Math.abs(avg - cHits(i))).sum / 2.0
    val maxExcedent = sum - avg
    val imbalance = excedent / maxExcedent
    1.0 - imbalance
  }
  def centerBoostFactor(iCenter:Int) = if(cHits.sum == 0) 1.0 else {
    val total = cHits.sum
    val share = cHits(iCenter)/total
    val boost = cHits.size * (1.0 - share)
    if(this.params.filterValue.contains(1))
      println(s"$iCenter, $total, $share, $boost")
    boost
  }
  def round(v:Double) = {
    import scala.math.BigDecimal
    BigDecimal(v).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
  }

  case class ScoredSequence( // score of sentence for particular class
    inClass:Int
    , outClass:Int
    , outScore:Double
    , scoredVectors:Iterator[ScoredVector])
  def scoreSequence(scoredTokens:Iterator[(Int, Iterator[ScoredVector])]) = {
    val pointScores = Array.fill(this.maxTopWords)(0.0) // claculates mean of scores of token to topwords
    val pointCounts = Array.fill(this.maxTopWords)(0) // how many tokens will be assigned to this topword
    //println("In function scoreSequence()")
    //println(s"this.outClasses: $this.outClasses")
    val sequenceScore = // returns two scoreSequence, which contain a particular class
     for((inClass, inClassScores)  <- scoredTokens)
        yield {
          //println(s"    inClass: $inClass, inClassScores: $inClassScores")
          val retTokens = this.outClasses.map(o => (o, ListBuffer[ScoredVector]())).toMap
          //println(s"    retTokens: $retTokens")
          val (bestClass, bestScore) = {
            for(scoredToken  <- inClassScores) { // returns only one ScoredSequence;
              // scoredToken.iPoint = index of the topword for this current token
              pointScores(scoredToken.iPoint) = pointScores(scoredToken.iPoint) + scoredToken.outScore
              pointCounts(scoredToken.iPoint) = pointCounts(scoredToken.iPoint) + 1
              retTokens(scoredToken.outClass) += scoredToken
              //println(s"pointScores(${scoredToken.iPoint}): ${pointScores(scoredToken.iPoint)}; pointCounts(scoredToken.iPoint): ${pointCounts(scoredToken.iPoint)}; retTokens(${scoredToken.outClass}): ${retTokens(scoredToken.outClass)}")
            }

            this.links(inClass).iterator
              .map{outClass =>
                var vectorCount = 0
                val pointScore = // score for each topword for this outClass
                  this.rel.get(outClass).map(_.keysIterator).getOrElse(Iterator[Int]())
                    .map{iPoint =>
                      val pointScore = if(pointCounts(iPoint) == 0) 0.0 else pointScores(iPoint)/pointCounts(iPoint)
                      vectorCount = vectorCount + 1
                      pointScore
                    }
                    .reduceOption(_ + _).getOrElse(0.0)
                (outClass, if(pointScore == 0) 0.0 else pointScore / vectorCount)
              }
              .reduce((p1, p2) => (p1, p2) match {case ((_, score1),(_, score2)) => if(score1 > score2) p1 else p2}) // take best score of both possible outClasses
          }
          //println("  ScoredSequence: inClass: $inClass, outClass: $bestClass, outScore: $bestScore")
          ScoredSequence(inClass = inClass, outClass = bestClass, outScore = bestScore, scoredVectors = retTokens(bestClass).iterator) // scoredVectors : score for each token
        }
    sequenceScore
  }
  case class ScoredVector(
    iVector:Int // index of the token in sentence
      , outClass:Int
      , outScore:Double
      , iPoint:Int // index of topword
      , iCenter:Int)
  def score( // evaluates model and fits token to node
    iVector:Int
    , vectors:Seq[MLVector]
    , vTokens:Seq[String]
    , inClass:Int
    , parent:Option[ClusteringNode]=None
    , cGenerator: Iterator[Int]
    , fit:Boolean) = {
    //println("\tSCORE in SCOREDVECTOR =====")
    //println(s"\tiVector: $iVector; inClass: $inClass, vTokens: ${vTokens.mkString(";")}")
    //val vectorOrCenter = asVCenter match {case Some(v) => v case None => vectors(iVector) }
    if(fit
        && cGenerator.hasNext // create children until limit is reached : maxClasses
        && this.children.size < this.classCentersMap.size // create children only if children do not exist already
        && !this.initializing
        && this.pScores.sum > this.childSplitSize // pScores: score for each point/topword ; sum(pScores) == number of documents went through this node
        && (parent.isEmpty || parent.get.cHits.forall(_ > this.childSplitSize))
        && (parent.isEmpty || this.clusterScore < 0.9) /*(this.clusterScore - parent.get.clusterScore)/parent.get.clusterScore > 0.02 )*/ 
      ) {
      //println(s"spawning... ${this.params.annotations}, ${this.inClasses}")
      this.fillChildren(cGenerator) // create children
    }
    onInit(vectors(iVector), Seq(vTokens(iVector)), inClass) // affecting top words for initalisation if not initialised
    //println(s"this.rel: ${this.rel}")
    this.links(inClass).iterator
      .filter{outClass => this.rel.contains(outClass)}
      .map{outClass =>
        val iCenter = this.classCenters(outClass)
        //println(s"\toutClass: $outClass, iCenter: $iCenter, this.rel(outClass): ${this.rel(outClass)}")
        val (bestPoint, pSimilarity) =
           (for(iPoint <- this.rel(outClass).keysIterator)
             yield (iPoint, vectors(iVector).similarityScore(this.points(iPoint)))
           )
            .reduce((p1, p2) => (p1, p2) match {
              case ((i1, score1), (i2, score2)) =>
                if(score1 > score2) p1
                else p2
            })
          //println(s"\toutClass: $outClass, outScore: pSimilarity, bestPoint: $bestPoint")
          ScoredVector(iVector = iVector, outClass = outClass, outScore = pSimilarity, iPoint = bestPoint, iCenter = iCenter) // evaluates current vector on each cluster class, returns two scoredvectors
        }
  }

  def affectPoint(vector:MLVector, tokens:Seq[String], vClass:Int, vScore:Double, iPoint:Int, iCenter:Int, weight:Double = 1.0, asVCenter:Option[MLVector]=None, fit:Boolean) {
    // associates a vector to a particular class
    //println("\taffectPoint ======")
    //println("tokens: "+tokens.mkString(" "))
    //println(s"vClass: $vClass, vScore: $vScore, iPoint: $iPoint, iCenter: $iCenter, weight: $weight")
    this.pScores(iPoint) = this.pScores(iPoint) + vScore * weight
    val vectorOrCenter =  asVCenter match {case Some(v) => v case _ => vector}
    //println(s"pScores($iPoint) : ${this.pScores(iPoint)}")
    // if not initializing
    if((this.sequences(iPoint).size != tokens.size
          || It.range(0, tokens.size).map(i => String.CASE_INSENSITIVE_ORDER.compare(this.sequences(iPoint)(i),tokens(i)) == 0).contains(false)
        )
        && !this.initializing
        && fit
      )
      tryAsPoint(vector = vector, tokens = tokens, vClass = vClass, iPoint = iPoint, iCenter = iCenter) // tries this point as a topword and checks if there is an improvement
    this.vCenters(iPoint) = this.vCenters(iPoint).scale(pScores(iPoint)/(pScores(iPoint) + weight)).sum(vectorOrCenter.scale(weight).scale(weight/(pScores(iPoint) + weight))) // update center/statistic for each topword
    this.pGAP(iPoint) = 1.0 - this.vCenters(iPoint).similarityScore(this.points(iPoint))
    //println(s"pGAP: ${this.pGAP(iPoint)}, iPoint: ${iPoint}, pScores: ${pScores(iPoint)}")
    // cError: average distance/error of all tokens to its closest topword
    this.cError(iCenter) = this.cError(iCenter) * (cHits(iCenter)/(cHits(iCenter) + weight)) + (1.0 - vectorOrCenter.similarityScore(this.points(iPoint))) * (weight/(cHits(iCenter) + weight))
    this.cHits(iCenter) = this.cHits(iCenter) + weight
  }
  def tryAsPoint(vector:MLVector, tokens:Seq[String], vClass:Int, iPoint:Int, iCenter:Int) {
    val newGAP = 1.0 - this.vCenters(iPoint).similarityScore(vector)
    if(newGAP - this.pGAP(iPoint) < 0) {
      /*if(Seq(2, 3).contains(vClass)){
        println(s"gap: ${this.pGAP(iPoint)}> $newGAP replacing ${this.sequences(iPoint)} ${newGAP - this.pGAP(iPoint)} by ${tokens} ${this.points(iPoint).similarityScore(vector)}")
      }*/
      this.points(iPoint) = vector
      this.sequences(iPoint) = tokens
      this.updateParams(None, false)
    }
  }

  def GAP = { // iPoint = top words
    val allScores = this.pScores.sum
    It.range(0, this.pGAP.size)
      .map{iPoint => this.pGAP(iPoint)*(this.pScores(iPoint)/allScores)}
      .reduce(_ + _)
  }

  def leafsGAP = {
    if(this.children.size > 0)
      this.children.map(c => c.clusteringGAP).sum
    else
      this.GAP match {
        case v if v.isInfinite || v.isNaN => 0.0
        case v => v
      }
  }
  def fromClass(toClass:Int) =
    this.links
      .toSeq
      .flatMap{case (from, to) =>
        if(to(toClass)) Some(from)
        else None
      }
      .head

  def mergeWith(thatNode:Node, cGenerator:Iterator[Int], fit:Boolean):this.type = {
    thatNode match {
      case that:ClusteringNode =>
        if(fit & that.children.filter(c => c.params.hits> 0).size > 1) { // you walk until non-empty leaf nodes
            that.children.filter(c => c.params.hits> 0).foreach(c => this.mergeWith(thatNode = c, cGenerator = cGenerator, fit = fit))
        } else if(fit) {
          val vectorsInScopeCount =  that.points.size
          val outToInClass =
            this.links.keysIterator
              .flatMap(thisIn => that.links(thisIn).map(thatOut => (thatOut, thisIn)))
              .toSeq
              .groupBy{case (thatOut, thisIn) => thatOut}
              .mapValues{s => s.map{case (thatOut, thisIn) => thisIn}.head}

          (for(iCenter <- It.range(0, this.numCenters))
            yield {
              val scoresByClass = that.classCentersMap(iCenter).iterator
                .map{leafClass =>
                   (outToInClass(leafClass)
                     , that.rel.get(leafClass).map(pairs => pairs.keysIterator).getOrElse(It[Int]()).map{ iLeafPoint =>
                        val scoredPoint =
                          this.score(
                            iVector = iLeafPoint
                            , vectors = that.points
                            , vTokens = that.sequences.map(t => t match {case Seq(t) => t case _ => throw new Exception("Multi token clustering not yet suported")})
                            , inClass = outToInClass(leafClass)
                            , parent = None/*parent match {case Some(c) => c match {case c:ClusteringNode => Some(c) case _ => None} case _ => None}*/
                            , cGenerator = cGenerator
                            , fit = fit
                          )
                        scoredPoint //score of one the 3 topwords of this to the topwords of that
                       }
                   )
                }

              val sequenceScore = this.scoreSequence(scoredTokens = scoresByClass.map{case (inClass, scores) => (inClass, scores.flatMap(s => s))})
              sequenceScore.map{case ScoredSequence(inClass, outClass, score, scoredVectors) =>
                for(ScoredVector(iVector, outVectorClass, outVectorScore, iPoint, iCenter) <- scoredVectors ) {
                  this.params.hits = this.params.hits + that.params.hits / vectorsInScopeCount
//                  TODO: merge externalClassesFreq for this that
//                  TODO: check if division
                  // recalculates centers of this based on that and checks if topwords are better
                  this.affectPoint(
                    vector = that.points(iVector)
                    , tokens = that.sequences(iVector)
                    , vClass = outClass
                    , vScore = outVectorScore
                    , iPoint = iPoint
                    , iCenter = iCenter
                    , weight = that.params.hits / vectorsInScopeCount
                    , asVCenter = Some(this.vCenters(iCenter))
                    , fit = fit
                  )
                }
                (outClass, score)
              }.reduce((p1, p2) => (p1, p2) match{case((_, score1),(_, score2)) => if(score1>score2)  p1 else p2})
            }).reduce((p1, p2) => (p1, p2) match{case((_, score1),(_, score2)) => if(score1>score2)  p1 else p2})
             match {
               case (outClass, _) =>
                 for(i <- It.range(0, this.children.size)) {
                   if(this.children(i).params.filterValue.contains(outClass))
                     (this.children(i)).mergeWith(thatNode, cGenerator = cGenerator, fit = fit)
                 }
             }
        }
        else {
          this.params.hits = this.params.hits + that.params.hits
//          TODO: merge externalClassesFreq for this that
//          TODO: calculate purity based on externalClassesFreq
//          this.params.purity = ..
          It.range(0, this.pScores.size).foreach(i => this.pScores(i) =  this.pScores(i) + that.pScores(i) )
          It.range(0, this.cError.size).foreach(i => this.cError(i) = this.cError(i) * (this.cHits(i)/(this.cHits(i) + that.cHits(i))) + that.cError(i) * (that.cHits(i)/(that.cHits(i) + this.cHits(i))))
          It.range(0, this.cHits.size).foreach(i => this.cHits(i) =  this.cHits(i) + that.cHits(i))
          It.range(0, this.vCenters.size).foreach(i => this.vCenters(i) = this.vCenters(i).scale(this.pScores(i)/(this.pScores(i) + that.pScores(i))).sum(that.vCenters(i).scale(that.pScores(i)/(that.pScores(i) + this.pScores(i)))))
          It.range(0, this.pGAP.size).foreach(i => 1.0 - this.vCenters(i).similarityScore(this.points(i)))
          It.range(0, this.children.size).foreach(i => this.children(i).mergeWith(that.children(i), cGenerator, fit))
          this
        }
      case _ => throw new Exception(s"Clustering node cannot learn from ${thatNode.getClass.getName}")
    }
    this
  }
  def resetHitsExtras {
    It.range(0, this.pScores.size).foreach(i => pScores(i) = 0.0)
    It.range(0, this.cHits.size).foreach(i => cHits(i) = 0.0)
    It.range(0, this.pGAP.size).foreach(i => pGAP(i) = 0.0)
    It.range(0, this.cError.size).foreach(i => cError(i) = 0.0)
    It.range(0, this.pGAP.size).foreach(i => pGAP(i) = 0.0)
  }
  def cloneUnfittedExtras = this.params.cloneWith(classMapping = None, unFit = true).get.toNode().asInstanceOf[this.type]
  def updateParamsExtras {
    this.params.cError = Some(this.cError.clone)
  }

  def fillChildren(cGenerator:Iterator[Int]) {
    // creates two empty children for current node and affects classes and parameters to collect sentences as expected
    for{i <- It.range(this.children.size, this.classCenters.values.toSet.size) if cGenerator.hasNext } {
      val fromMap =
        this.classCenters.flatMap{ case (outClass, iCenter) =>
          if(iCenter == i)
            this.links.flatMap{case (from, toSet) => if(toSet(outClass)) Some(from) else None}.head match {case from => Some(from, outClass)}
          else None
        }.toMap
      val toMap = this.outClasses.iterator.zip(cGenerator).toSeq.toMap
      val filterMap =
        this.classCenters
          .filter{ case (outClass, iCenter) => iCenter == i}
          .zipWithIndex
          .map{case ((outClass, iCenter), j) => (this.params.filterValue(j), outClass)}
          .toMap
      this.children ++=  this.params.cloneWith(classMapping = Some(fromMap ++ toMap ++ filterMap), unFit = true).map(p => p.toNode())
      val hitDiff = this.params.hits - It.range(0, this.children.size).map(i => this.children(i).params.hits).sum
      val initHits = hitDiff /  It.range(0, this.children.size).filter(i => this.children(i).params.hits == 0).size
      It.range(0, this.children.size).filter(i => this.children(i).params.hits == 0).foreach(i => this.children(i).params.hits = initHits)
    }
    if(this.children.size < this.classCenters.values.toSet.size) {
      this.children.clear
    }
    //println(s"My hits = ${this.params.hits} ==> New hits ${this.children.map(c => c.params.hits).sum}")
  }
}
object ClusteringNode {
  def apply(params:NodeParams, index:Option[VectorIndex]):ClusteringNode = {
    val ret = ClusteringNode(
      points = ArrayBuffer[MLVector]()
      , params = params
    )
    index match {
      case Some(ix) if ret.sequences.size > 0 =>
        ret.points ++= (ix(ret.sequences.flatMap(t => t).distinct) match {case map => ret.sequences.map(tts => tts.flatMap(token => map.get(token)).reduceOption(_.sum(_)).getOrElse(null))})
      case _ =>
    }
    ret
  }
  def apply(encoded:EncodedNode):ClusteringNode = {
    val ret = ClusteringNode(
      points = encoded.points
      , params = encoded.params
    )
    //these two are not necessary if params are updated at encode time comment after test
    encoded.deserialize[Array[Double]]("pScores").zipWithIndex.foreach{case (v, i) => ret.pScores(i) = v}
    encoded.deserialize[Array[Double]]("cError").zipWithIndex.foreach{case (v, i) => ret.cError(i) = v}

    ret.initializing = encoded.deserialize[Boolean]("initializing")
    if(encoded.points.size > 0) encoded.deserialize[ArrayBuffer[MLVector]]("vCenters").zipWithIndex.foreach{case (v, i) => ret.vCenters(i) = v}
    encoded.deserialize[ArrayBuffer[Double]]("pGAP").zipWithIndex.foreach{case (v, i) => ret.pGAP(i) = v}
    encoded.deserialize[ArrayBuffer[Double]]("cHits").zipWithIndex.foreach{case (v, i) => ret.cHits(i) = v}
    ret
  }
}
