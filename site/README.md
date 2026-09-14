# site/

The landing page at https://viberfasend.github.io/cadence/ — one `index.html`, no build step,
deployed by `.github/workflows/pages.yml` on every push to `main` that touches this directory.

Everything in it is derived from the app rather than invented for the page: the palette is
`ui/theme/Color.kt`'s Material scheme, the chip colours are quick-add's token colours, the
example list follows the sort rule, and the download links are the permanent
`releases/latest/download/…` URLs the README uses. When a fact about the app changes, this page
is one of the places that has to change with it.
