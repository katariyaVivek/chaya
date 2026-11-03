// The person's own videos and sounds (`own.rs` in the app): dropped on the window from Explorer, or picked with
// *Add from this PC*, and listed where they are, as finished entries the player opens. Nothing is copied or uploaded.
//
// A drop reaches the window as Tauri's drag-and-drop events: the window's own (`onDragDropEvent`), and, over a page in
// the built-in browser, the page's, which the app hands on as `page-drop` events (the page itself never gets the file).
//
// The words (`noteFor`, `dropTitle`) are plain functions, tested with Node (`test/own.test.mjs`); `ownVideos` puts them
// in the window.

/** How long a drag may go without a word from Windows before the drop target is taken down anyway. */
export const QUIET = 3000;

function counted(n, one, many) {
  return n === 1 ? `a ${one}` : `${n} ${many}`;
}

/** "2 videos and a sound", for the files in `tasks`. */
function what(tasks) {
  const videos = tasks.filter((t) => t.kind !== 'audio').length;
  const sounds = tasks.length - videos;
  const parts = [];
  if (videos) parts.push(counted(videos, 'video', 'videos'));
  if (sounds) parts.push(counted(sounds, 'sound', 'sounds'));
  return parts.join(' and ');
}

/**
 * What adding files came to (`add_own_videos`' answer: `tasks` listed, `already` the numbers of those that were
 * listed before, `refused` why the rest were not), as `{ text, open }`: the words for the note, and the one entry to
 * open in the player straight away when a single video or sound was added and nothing refused, else null.
 */
export function noteFor({ tasks, already, refused }) {
  const fresh = tasks.filter((t) => !already.includes(t.id));
  const old = tasks.filter((t) => already.includes(t.id));
  const parts = [];
  if (fresh.length === 1) parts.push(`Added ${fresh[0].fileName}. It stays where it is on this PC.`);
  else if (fresh.length > 1) parts.push(`Added ${what(fresh)}. They stay where they are on this PC.`);
  if (old.length === 1) parts.push(`${old[0].fileName} was already in the list.`);
  else if (old.length > 1) parts.push(`${old.length} were already in the list.`);
  if (refused.length > 2) parts.push(...refused.slice(0, 2), `${refused.length - 2} more weren't added.`);
  else parts.push(...refused);
  const open = tasks.length === 1 && refused.length === 0 ? tasks[0].id : null;
  return { text: parts.join(' '), open };
}

/** What the drop target says while `count` files (0: not known yet) are dragged over the window. */
export function dropTitle(count) {
  if (count > 1) return `Drop to add these ${count} files`;
  return count === 1 ? 'Drop to add it to Chaya' : 'Drop to add to Chaya';
}

/**
 * Takes drops and *Add from this PC*. `tauri` is the app's API; `drop` the drop target, `button` *Add from this PC*,
 * `note` where what happened is said; `added(tasks)` lists the entries straight away (their `download` events may come
 * after); `open(id)` plays one; `shown()` brings the list into view (over a page, it is hidden).
 */
export function ownVideos({ tauri, drop, button, note, added, open, shown }) {
  let quiet = 0;
  let noteTimer = 0;

  function over(count) {
    if (count !== null) drop.querySelector('.drop__title').textContent = dropTitle(count);
    drop.hidden = false;
    clearTimeout(quiet);
    quiet = setTimeout(leave, QUIET);
  }

  function leave() {
    clearTimeout(quiet);
    drop.hidden = true;
  }

  function say(text) {
    note.querySelector('span').textContent = text;
    note.hidden = !text;
    clearTimeout(noteTimer);
    noteTimer = setTimeout(() => (note.hidden = true), 12000);
  }

  function heard({ type, paths }) {
    if (type === 'enter') over(paths?.length ?? 0);
    else if (type === 'over') over(null);
    else if (type === 'leave') leave();
    else if (type === 'drop') {
      leave();
      if (paths?.length) add(tauri.core.invoke('add_own_videos', { paths }));
    }
  }

  async function add(asked) {
    let answer;
    try {
      answer = await asked;
    } catch (message) {
      say(String(message));
      shown();
      return;
    }
    if (!answer || (!answer.tasks.length && !answer.refused.length)) return;
    added(answer.tasks);
    const { text, open: one } = noteFor(answer);
    say(text);
    if (one !== null) open(one);
    else shown();
  }

  tauri?.webview?.getCurrentWebview?.().onDragDropEvent(({ payload }) => heard(payload)).catch(() => {});
  tauri?.event.listen('page-drop', ({ payload }) => heard(payload));
  button.addEventListener('click', () => add(tauri.core.invoke('choose_own_videos')));
  note.querySelector('button').addEventListener('click', () => (note.hidden = true));
}
