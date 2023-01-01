/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

/**
 * Drop a link anywhere on the page and we will open the add and create torrent sections
 * and put it in the add torrent form.
 *
 * Drop a file anywhere on the page and we will open the add and create torrent sections
 * and throw up an alert telling you to pick one.
 *
 * ref: https://developer.mozilla.org/en-US/docs/Web/API/HTML_Drag_and_Drop_API/File_drag_and_drop
 *
 * @since 0.9.57
 */
function initDND()
{
	var add = document.getElementById("toggle_addtorrent");
	if (add != null) {
		// we are on page one
		var create = document.getElementById("toggle_createtorrent");
		var div = document.getElementById("page");
		var form1 = document.getElementById("nofilter_newURL");
		var form2 = document.getElementById("nofilter_newDir");
		var form3 = document.getElementById("nofilter_baseFile");
		var addbutton = document.getElementById("addButton");
		var createbutton = document.getElementById("createButton");

		div.addEventListener("drop", function(event) {
			var name = "";
			var isURL = false;
			var isDir = false;
			if (event.dataTransfer.items) {
				// Use DataTransferItemList interface to access the file(s)
				var item = event.dataTransfer.items[0];
				// Chrome bug
				// https://bugs.chromium.org/p/chromium/issues/detail?id=1363757
				// sometimes undefined for directories, throws uncaught TypeError
				if (((typeof item.kind) !== "undefined") && (item.kind === "file")) {
					var file = item.getAsFile();
					if (file.size == 0)
						isDir = true;
					name = file.name;
				} else {
					// If dropped items aren't files, maybe they are URLs
					// we're going here in chrome for files usually
					name = event.dataTransfer.getData("URL");
					if (name.length > 0) {
						isURL = true;
					}
					// else chrome bug
					// https://bugs.chromium.org/p/chromium/issues/detail?id=1363757
				}
			} else {
				// Use DataTransfer interface to access the file(s)
				var file = event.dataTransfer.files[0];
				if (file.size == 0)
					isDir = true;
				name = file.name;
			}
			// set name in form
			if (name.length > 0) {
				if (isURL) {
					event.preventDefault();
					// prevent inadvertent drag-from-self
					var url = new URL(name);
					var us = new URL(document.location.href);
					if (url.origin === us.origin) {
						form1.value = "";
						event.dataTransfer.dropEffect = "none";
					} else {
						form1.value = name;
						addbutton.classList.add("highlight");
					}
				} else {
					form1.value = "";
					form2.value = "";
				   /*
					if (event.target.id != "nofilter_newDir" && event.target.id != "nofilter_baseFile") {
						// we don't get here any more, because dragover sets dropEffect="none"
						// boo, we can't get the full path
						// user must hit the target, either datadir or basefile
						event.preventDefault();
						// TODO translate
						if (isDir) {
							alert("Drop the directory \"" + name + "\" in \"Data dir\" to add a torrent or \"Data to seed\" to create a torrent");
						} else {
							// not a directory, can't be add torrent
							alert("Drop the file \"" + name + "\" in \"Data to seed\" to create a torrent");
						}
					}
				     */
				}
			} else {
				// Chrome bug
				// https://bugs.chromium.org/p/chromium/issues/detail?id=1363757
				console.info("Chrome bug? File drag and drop not supported on this browser");
			}
		});

		div.addEventListener("dragover", function(event) {
			event.preventDefault();
			// Setting to "none" causes the snap-back effect when dropped.
			// The drop event will not be fired, and we won't popup the alert.
			var fileRequired = (event.target.id === "nofilter_newDir" || event.target.id === "nofilter_baseFile");
			if (event.dataTransfer.items) {
				var item = event.dataTransfer.items[0];
				// needed for Chrome
				if (((typeof item.kind) !== "undefined") && (item.kind === "file")) {
					if (fileRequired) {
						event.dataTransfer.dropEffect = "copy";
					} else {
						event.dataTransfer.dropEffect = "none";
					}
				} else {
					if (fileRequired) {
						event.dataTransfer.dropEffect = "none";
					} else {
						event.dataTransfer.dropEffect = "link";
					}
				}
			} else {
				if (fileRequired) {
					event.dataTransfer.dropEffect = "copy";
				} else {
					event.dataTransfer.dropEffect = "none";
				}
			}
		});

		div.addEventListener("dragenter", function(event) {
			event.preventDefault();
			// expand one or both sections and scroll to view
			if (event.dataTransfer.items) {
				var item = event.dataTransfer.items[0];
				if (((typeof item.kind) !== "undefined") && (item.kind === "file")) {
					create.checked = true;
					form1.classList.remove("highlight");
					form2.classList.add("highlight");
					form3.classList.add("highlight");
				} else {
					create.checked = false;
					form1.classList.add("highlight");
					form2.classList.remove("highlight");
					form3.classList.remove("highlight");
				}
			} else {
				create.checked = true;
				form1.classList.remove("highlight");
				form2.classList.add("highlight");
				form3.classList.add("highlight");
			}
			add.checked = true;
			add.scrollIntoView(true);
		});

		form1.addEventListener("change", function(event) {
			if (form1.value.length > 0) {
				form1.classList.remove("highlight");
				form2.classList.remove("highlight");
				addbutton.classList.add("highlight");
			} else {
				addbutton.classList.remove("highlight");
			}
		});

		form2.addEventListener("change", function(event) {
			if (form2.value.length > 7 && form2.value.startsWith("file://")) {
				form2.value = form2.value.substring(7);
			}
			if (form1.value.length > 0) {
				form1.classList.remove("highlight");
				form2.classList.remove("highlight");
				addbutton.classList.add("highlight");
			} else {
				addbutton.classList.remove("highlight");
			}
		});

		form3.addEventListener("change", function(event) {
			if (form3.value.length > 7 && form3.value.startsWith("file://")) {
				form3.value = form3.value.substring(7);
			}
			if (form3.value.length > 0) {
				form3.classList.remove("highlight");
				createbutton.classList.add("highlight");
			} else {
				createbutton.classList.remove("highlight");
			}
		});

		form1.addEventListener("blur", function(event) {
			if (form1.value.length > 0) {
				form1.classList.remove("highlight");
				form2.classList.remove("highlight");
				addbutton.classList.add("highlight");
			} else {
				addbutton.classList.remove("highlight");
			}
		});

		form2.addEventListener("blur", function(event) {
			if (form1.value.length > 0) {
				form1.classList.remove("highlight");
				form2.classList.remove("highlight");
				addbutton.classList.add("highlight");
			} else {
				addbutton.classList.remove("highlight");
			}
		});

		form3.addEventListener("blur", function(event) {
			if (form3.value.length > 0) {
				form3.classList.remove("highlight");
				createbutton.classList.add("highlight");
			} else {
				createbutton.classList.remove("highlight");
			}
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
