// How far subtitles being made have got, as the window says it (`main.js`'s making card and download row): getting
// ready while whisper.cpp loads the model (on a graphics card it also prepares the card, which can take a while the
// first time), then how much of the sound it has heard and about how long is left, then finishing while it writes
// the file. The time left comes from how fast it has gone since it first moved.

/** Before this much has been heard, and this long gone by, there is too little to say how long is left. */
export const FEW_POINTS = 5;
export const FEW_SECONDS = 5;

/**
 * About how many seconds are left: the pace since `from` (`{ at, percent }`, when it was first seen moving, `at` in
 * milliseconds) kept up to 100%; `null` when there is too little to go on yet.
 */
export function secondsLeft(from, now, percent) {
  if (!from || percent >= 100) return null;
  const gone = (now - from.at) / 1000;
  const done = percent - from.percent;
  if (done < FEW_POINTS || gone < FEW_SECONDS) return null;
  return (gone / done) * (100 - percent);
}

/** What follows "Making subtitles on …" while listening: getting ready, the percentage and time left, or finishing. */
export function listeningWords(percent, left, timeLeft) {
  if (percent <= 0) return 'Getting ready';
  if (percent >= 99) return 'Finishing';
  return left == null ? `${percent}%` : `${percent}% · about ${timeLeft(left)} left`;
}
