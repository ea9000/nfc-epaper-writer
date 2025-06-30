// @ts-check
const canvas = document.querySelector('canvas');
const ctx = canvas.getContext('2d');
const textInput = document.querySelector('textarea');
const defaultText = textInput.value;
let inverted = false;
let bgColor = 'white';
let textColor = 'black';

const commonInstance = new NfcEIWCommon(canvas);
const clearCanvas = commonInstance.clearCanvas.bind(commonInstance);

/**
 * NEW version with Word Wrapping
 * @param {string} text The full text to draw.
 * @param {string} fontFace The font to use.
 * @param {number} paddingW The padding on the left and right.
 */
function fitAndFillText(text, fontFace = 'Arial', paddingW = 4) {
    if (!text) {
        clearCanvas();
        return;
    }

    const { height: canvasH, width: canvasW } = canvas;
    const availableWidth = canvasW - (paddingW * 2);

    // --- Font Size Calculation ---
    // This is a simple approach; a more complex one could adjust size based on text length.
    let fontSize = 20; // A fixed font size often works best for wrapping.
    ctx.font = fontSize + `px ${fontFace}`;
    ctx.fillStyle = textColor;
    ctx.textBaseline = 'top'; // Use top baseline for easier y-coordinate calculation
    const lineHeight = fontSize * 1.2; // Spacing between lines

    // --- Word Wrapping Logic ---
    const words = text.split(' ');
    let line = '';
    let y = paddingW; // Start Y position

    for (let n = 0; n < words.length; n++) {
        const testLine = line + words[n] + ' ';
        const metrics = ctx.measureText(testLine);
        const testWidth = metrics.width;
        if (testWidth > availableWidth && n > 0) {
            // The line is full, draw it
            ctx.fillText(line, paddingW, y);
            // Start a new line
            line = words[n] + ' ';
            y += lineHeight;
        } else {
            // Word fits, add it to the current line
            line = testLine;
        }
    }
    // Draw the last remaining line
    ctx.fillText(line, paddingW, y);
}


/**
 * Normal operation is black text on white, but you can set inverted
 * @param {boolean} [updatedInverted]
 */
function setInverted(updatedInverted) {
    inverted = typeof updatedInverted === 'boolean' ? updatedInverted : !inverted;
    if (inverted) {
        bgColor = 'black';
        textColor = 'white';
    } else {
        bgColor = 'white';
        textColor = 'black';
    }
    renderToCanvas();
}

function drawBg() {
    ctx.fillStyle = bgColor;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
}

function renderToCanvas() {
    clearCanvas();
    drawBg();
    fitAndFillText(textInput.value); // Use the new wrapping function
}

renderToCanvas();
setTimeout(renderToCanvas, 200);

// Attach listeners
textInput.addEventListener('keyup', renderToCanvas);
textInput.addEventListener('input', renderToCanvas); // Also listen to 'input' for programmatic changes
document.querySelector('button#addLineBreak').addEventListener('click', () => {
    textInput.value += '\n';
    renderToCanvas();
});
document.querySelector('button#reset').addEventListener('click', () => {
    textInput.value = defaultText;
    setInverted(false);
    renderToCanvas();
});
document.querySelector('button#setInverted').addEventListener('click', () => {
    setInverted();
});