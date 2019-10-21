package demy.mllib.topic
import java.sql.Timestamp 

case class Annotation(
  tokens:Seq[String]
  , tag:Int
  , from:Option[Seq[String]]
  , inRel:Boolean
  , score:Double
) {
  def toAnnotationSource(tagMap:Map[Int, String])= 
    AnnotationSource(
      tokens = tokens
      , tag = tagMap(tag)
      , from = from
      , inRel = inRel
      , score = score  
      , sourceId = None
      , allTokens = None
      , tokensIndexes = None
      , fromIndexes = None
      , timestamp = new Timestamp(System.currentTimeMillis())
    )
}


case class AnnotationSource(
  tokens:Seq[String]
  , tag:String
  , from:Option[Seq[String]]
  , inRel:Boolean
  , score:Double
  , sourceId:Option[String]
  , allTokens:Option[Seq[String]]
  , tokensIndexes:Option[Seq[Int]]
  , fromIndexes:Option[Seq[Int]]
  , timestamp:Timestamp
) {
  def resetTimestamp = 
    AnnotationSource(
      tokens = tokens
      , tag = tag
      , from = from
      , inRel = inRel
      , score = score  
      , sourceId = sourceId
      , allTokens = allTokens
      , tokensIndexes = tokensIndexes
      , fromIndexes = fromIndexes
      , timestamp = new Timestamp(System.currentTimeMillis())
    )

  def toAnnotation(tagMap:Map[String, Int]) = Annotation(tokens = tokens, tag = tagMap(tag), from = from, inRel = inRel, score = score)
  def key = (Seq(tag) ++ tokens ++ from.getOrElse(Seq[String]())).mkString(" ")
  def mergeWith(that:AnnotationSource) = { 
    val (newer, older) = if(this.timestamp.after(that.timestamp)) (this, that) else (that, this)
    AnnotationSource(
      tokens = newer.tokens
      , tag = newer.tag
      , from = newer.from
      , inRel = newer.inRel
      , score = newer.score  
      , sourceId = newer.sourceId.orElse(older.sourceId)
      , allTokens = newer.allTokens.orElse(older.allTokens)
      , tokensIndexes = newer.tokensIndexes.orElse(older.tokensIndexes)
      , fromIndexes = newer.fromIndexes.orElse(older.fromIndexes)
      , timestamp = newer.timestamp
    )
  }
}
