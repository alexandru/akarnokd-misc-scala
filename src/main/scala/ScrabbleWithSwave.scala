import java.io.File
import java.util._
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import swave.core._

import scala.collection.mutable
import scala.io.Source

/**
  * Created by akarnokd on 2016.11.18..
  */
object ScrabbleWithSwave {

  var letterScores = Array(
    // a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p,  q, r, s, t, u, v, w, x, y,  z
    1, 3, 3, 2, 1, 4, 2, 4, 1, 8, 5, 1, 3, 1, 1, 3, 10, 1, 1, 1, 1, 4, 4, 8, 4, 10);

  var scrabbleAvailableLetters = Array(
    // a, b, c, d,  e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w, x, y, z
    9, 2, 2, 1, 12, 2, 3, 2, 9, 1, 1, 4, 2, 6, 8, 2, 1, 6, 4, 6, 4, 2, 2, 1, 2, 1);


  var scrabbleWords: mutable.Set[String] = null;
  var shakespeareWords: mutable.Set[String] = null;

  @volatile var result : Any = null;

  def main(args: Array[String]): Unit = {

    val n = 1;

    scrabbleWords = new mutable.HashSet[String]();

    for (line <- Source.fromFile(new File("files/ospd.txt")).getLines()) {
      scrabbleWords ++ line.toLowerCase()
    }

    shakespeareWords = new mutable.HashSet[String]();
    for (line <- Source.fromFile(new File("files/words.shakespeare.txt")).getLines()) {
      shakespeareWords ++ line.toLowerCase()
    }

    System.out.println(scrabble(false));

    val times = new ArrayList[Long]();

    for (i <- 1 to n) {
      val start = System.nanoTime();
      result = scrabble(false);
      val end = System.nanoTime();

      times.add(end - start)
    }

    times.sort(new Comparator[Long] {
      override def compare(o1: Long, o2: Long): Int = o1.compareTo(o2)
    });

    System.out.print("%,2f ms%n".format(times.get(times.size() / 2) / 1000000.0));

    times.clear()

    System.out.println(scrabble(true));

    for (i <- 1 to n) {
      val start = System.nanoTime();
      result = scrabble(true);
      val end = System.nanoTime();

      times.add(end - start)
    }

    times.sort(new Comparator[Long] {
      override def compare(o1: Long, o2: Long): Int = o1.compareTo(o2)
    });

    System.out.print("%,2f ms%n".format(times.get(times.size() / 2) / 1000000.0));
  }

  def scrabble(double: Boolean) : Any = {

    val scoreOfALetter = (letter: Int) => {
      letterScores(letter - 'a')
    }

    val letterScore = (entry: Map.Entry[Int, Long]) => {
      letterScores(entry.getKey() - 'a') *
      Integer.min(entry.getValue().intValue(), scrabbleAvailableLetters(entry.getKey() - 'a'))
    }

    val toInteger = (string: String) => {
      Spout(0 to string.length - 1).map((v: Int) => string.charAt(v))
    }

    val histoOfLetters = (word: String) => {
      val map = new HashMap[Int, Long]();
      toInteger(word).map((value: Char) => {
        val current = map.get(value);
        if (current == null) {
          map.put(value, 1L)
        } else {
          map.put(value, current + 1L)
        }

      }).takeLast(1)
    }

    val blank = (entry: mutable.HashEntry[Int, Long]) => {
      Math.max(0L, entry.next - scrabbleAvailableLetters(entry.key - 'a'))
    }

    val nBlanks = (word: String) => {
      histoOfLetters(word)
        .flattenConcat()
        .map(blank)
        .reduce((a, b) => a + b)
    }

    val checkBlanks = (word: String) => nBlanks(word).map((v: Long) => v <= 2L)

    val score2 = (word: String) => {
      histoOfLetters(word)
        .flattenConcat()
        .map(letterScore)
        .reduce((a, b) => a + b)
    }

    val first3 = (word: String) => toInteger(word).take(3)

    val last3 = (word: String) => toInteger(word).drop(3)

    val toBeMaxed = (word: String) => Spout(first3, last3).flattenConcat()

    val bonusForDoubleLetter = (word: String) =>
      toBeMaxed.apply(word).map(scoreOfALetter).reduce((a, b) => Math.max(a, b))

    val score3 = (word: String) => {
      if (double) {
        Spout(
          score2(word), score2(word),
          bonusForDoubleLetter(word), bonusForDoubleLetter(word),
          Spout(word.length).map((v) => {
            if (v == 7) {
              50
            } else {
              0
            }
          })
        )
          .flattenConcat()
          .reduce((a, b) => a + b)
      } else {
        Spout(
          score2(word).map((v) => v * 2),
          bonusForDoubleLetter(word).map((v) => v * 2),
          Spout(word.length).map((v) => {
            if (v == 7) {
              50
            } else {
              0
            }
          })
        )
          .flattenConcat()
          .reduce((a, b) => a + b)
      }
    }

    val buildHistoOnScore = (score: (String) => Spout[Int]) => {
      val map = new TreeMap[Int, List[String]](new Comparator[Int]() {
        override def compare(o1: Int, o2: Int): Int = Integer.compare(o2, o1)
      })

      Spout.fromIterable(shakespeareWords)
        .filter((word) => scrabbleWords.contains(word))
        .filter((word) => first(checkBlanks(word)))
        .map((word) => {
          val key = first(score(word))
          var list = map.get(key);
          if (list == null) {
            list = new ArrayList[String]();
            map.put(key, list)
          }
          list.add(word);
          return map;
        })
        .takeLast(1)
    }

    val finalList = new ArrayList[Map.Entry[Int, List[String]]]()

    first(
      buildHistoOnScore(score3)
        .flattenConcat()
        .take(3)
        .map((v) => {
          finalList.add(v)
          finalList
        })
        .takeLast(1)
    )
  }

  def flatten[T](source: Spout[Spout[T]]) : Spout[T] = {

  }

  def first[T](source: Spout[T]): T = {
    val cdl = new CountDownLatch(1);
    val value = new AtomicReference[T]();

    source.first.foreach((e) => {
      value.lazySet(e);
      cdl.countDown()
    })

    cdl.await();
     value.get()
  }
}