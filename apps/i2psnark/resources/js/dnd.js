/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

/*
 * Drop a link anywhere on the page and we will open the add and create torrent sections
 * and put it in the add torrent form.
 *
 * Drop a file anywhere on the page and we will open the add and create torrent sections
 * and throw up an alert telling you to pick one.
 *
 * ref: https://developer.mozilla.org/en-US/docs/Web/API/HTML_Drag_and_Drop_API/File_drag_and_drop
 */

function initDND()
{
	var add = document.getElementById("toggle_addtorrent");
	if (add != null) {
		// we are on page one
		var create = document.getElementById("toggle_createtorrent");
		var div = document.getElementById("page");
		div.addEventListener("drop", function(event) {
                        var name = "";
			var isURL = false;
			var isDir = false;
			if (event.dataTransfer.items) {
				// Use DataTransferItemList interface to access the file(s)
				var item = event.dataTransfer.items[0];
				// If dropped items aren't files, reject them
				if (item.kind === 'file') {
					const file = item.getAsFile();
					if (file.size == 0)
						isDir = true;
					name = file.name;
				} else {
					name = event.dataTransfer.getData("URL");
					isURL = true;
				}
			} else {
				// Use DataTransfer interface to access the file(s)
				const file = event.dataTransfer.files[0];
				if (file.size == 0)
					isDir = true;
				name = file.name;
			}
			// expand sections and scroll to view
			add.checked = true;
			create.checked = true;
			add.scrollIntoView(true);
			// set name in form
			if (name.length > 0) {
				if (isURL) {
					event.preventDefault();
					var form = document.getElementById("nofilter_newURL");
					form.value = name;
				} else {
					var form = document.getElementById("nofilter_newDir");
					form.value = "";
					form = document.getElementById("nofilter_baseFile");
					form.value = "";
					if (event.target.id != "nofilter_newDir" && event.target.id != "nofilter_baseFile") {
						// boo, we can't get the full path
						// user must hit the target, either datadir or basefile
						event.preventDefault();
						// TODO translate
						if (isDir) {
							alert("Please drop the directory \"" + name + "\" in \"Data dir\" to add a torrent or \"Data to seed\" to create a torrent");
						} else {
							// not a directory, can't be add torrent
							alert("Please drop the file \"" + name + "\" in \"Data to seed\" to create a torrent");
						}
					}
				}
			}
		});
		div.addEventListener("dragover", function(event) {
			event.preventDefault();
			// expand sections and scroll to view
			add.checked = true;
			create.checked = true;
			add.scrollIntoView(true);
		});
		div.addEventListener("dragenter", function(event) {
			event.preventDefault();
			// expand sections and scroll to view
			add.checked = true;
			create.checked = true;
			add.scrollIntoView(true);
		});
	} else {
		// we are not on page one
		// TODO
	}
}

document.addEventListener("DOMContentLoaded", function() {
    initDND();
}, true);

/* @license-end */
