// The window's words for the person's own videos (ui/own.js): what adding files came to, and what the drop target
// says. Run with: node --test test/own.test.mjs, in desktop/app

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { noteFor, dropTitle } from '../ui/own.js';

const task = (id, fileName, kind = 'video') => ({ id, fileName, kind });

test('one file added opens in the player, and is said to stay where it is', () => {
  const added = noteFor({ tasks: [task(4, 'Team meeting.mp4')], already: [], refused: [] });
  assert.deepEqual(added, { text: 'Added Team meeting.mp4. It stays where it is on this PC.', open: 4 });
});

test('a file already listed opens too, and is said to be there already', () => {
  const added = noteFor({ tasks: [task(2, 'Talk.mkv')], already: [2], refused: [] });
  assert.deepEqual(added, { text: 'Talk.mkv was already in the list.', open: 2 });
});

test('several files are counted by kind, and none opens', () => {
  const tasks = [task(5, 'a.mp4'), task(6, 'b.mov'), task(7, 'c.mp3', 'audio'), task(1, 'd.mp4'), task(2, 'e.mp4')];
  assert.deepEqual(noteFor({ tasks, already: [1, 2], refused: [] }), {
    text: 'Added 2 videos and a sound. They stay where they are on this PC. 2 were already in the list.',
    open: null,
  });
  assert.equal(noteFor({ tasks: [task(5, 'a.m4a', 'audio'), task(6, 'b.m4a', 'audio')], already: [], refused: [] }).text,
    'Added 2 sounds. They stay where they are on this PC.');
});

test('files not added say why; past two, the rest are counted', () => {
  const one = noteFor({ tasks: [task(3, 'Talk.mp4')], already: [], refused: ["Notes.txt isn't a video or a sound Chaya can open."] });
  assert.deepEqual(one, {
    text: "Added Talk.mp4. It stays where it is on this PC. Notes.txt isn't a video or a sound Chaya can open.",
    open: null,
  });
  const refused = ['a.txt is not.', 'b.txt is not.', 'c.txt is not.', 'd.txt is not.'];
  assert.equal(noteFor({ tasks: [], already: [], refused }).text, "a.txt is not. b.txt is not. 2 more weren't added.");
});

test('the drop target says how many files are over the window', () => {
  assert.equal(dropTitle(1), 'Drop to add it to Chaya');
  assert.equal(dropTitle(3), 'Drop to add these 3 files');
  assert.equal(dropTitle(0), 'Drop to add to Chaya');
});
