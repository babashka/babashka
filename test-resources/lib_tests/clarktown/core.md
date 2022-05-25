Lorem ipsum dolor **sit** amet. Lorem ipsum *dolor* _sit_ __amet__.

There's a [link here](https://example.com/that_has_things?!???!#in-it).

1. List item
2. Another list item
    1. Sub list item
    2. Another sub list item
        1. Sub sub list item
    3. Continuing sub list item
3. Continuing list item

```javascript
// Detect horizontal line block
function isHorizontalLineBlock(block) {
  return block === "***";
}

// Render horizontal line block
function horizontalLineBlock(block) {
  return `<hr>`;
}

// Compose an array of parsers
const parsers = [{
  matcher: isHorizontalLineBlock,
  renderers: [horizontalLineBlock]
}];

// And finally, our parser itself
function markdownToHTML(markdown) {
  // Create blocks
  const blocks = content.split(/\n\n/);

  // Parse blocks
  const parsedBlocks = blocks.map((block) => {
    // Let's find a parser that has a matcher that matches
    const parser = parsers.find((parser) => parser.matcher(block));

    // If match was found, let's run our renderers over `block`
    if (parser) {
      for (const renderer of match.renderers) {
        block = renderer(block);
      }
    }

    return block;
  });

  // And at last, join the blocks together for one big block.
  return parsedBlocks.join("");
}
```

- Test 123
- Test 223
    - Test 334
        1. Test test

This is ___bold italic text___ and ***this is also***. *What about italic text that **has bold text***?

## Hi there, world!

* List item
* Another list ~~item~~
    * Sub list item
    * Another sub list item
        * Sub sub list item
        * Continuing sub list item
* Continuing list item

***

* List item
* Another list item
    * Sub list item
    * Another sub list item
        1. Sub sub list item
        2. Continuing sub list item
* Continuing list item

This is a H1 heading with settext
=================================

And this is a H2 heading with settext
-------------------------------------

Testing paragraph right before a code block
```
code goes here
```
# Heading goes here
Paragraph right after heading
