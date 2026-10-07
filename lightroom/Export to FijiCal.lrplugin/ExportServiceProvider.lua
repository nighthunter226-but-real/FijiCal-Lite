-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 nighthunter226-but-real and FijiCal Lite contributors.
-- Distributed without warranty; see LICENSE and COPYRIGHT.md.
local LrDialogs = import 'LrDialogs'
local LrFileUtils = import 'LrFileUtils'
local LrPathUtils = import 'LrPathUtils'
local LrPrefs = import 'LrPrefs'
local LrShell = import 'LrShell'
local LrView = import 'LrView'

local prefs = LrPrefs.prefsForPlugin()

local function defaultOutputFolder()
    return LrPathUtils.child(LrPathUtils.getStandardFilePath('pictures'), 'FijiCal Exports')
end

local function defaultExecutable()
    local candidates = {
        'H:\\FijiCal Lite 0.9 - Mamanuca\\FijiCal Lite\\FijiCal Lite.exe',
        LrPathUtils.child(LrPathUtils.getStandardFilePath('documents'), 'FijiCal Lite\\FijiCal Lite.exe'),
    }
    for _, path in ipairs(candidates) do
        if path and LrFileUtils.exists(path) == 'file' then return path end
    end
    return ''
end

local function executablePath(propertyTable)
    local value = propertyTable.fijicalExecutable
    if value and value ~= '' then return value end
    if prefs.executablePath and prefs.executablePath ~= '' then return prefs.executablePath end
    return defaultExecutable()
end

local function validate(propertyTable)
    local path = executablePath(propertyTable)
    if path == '' then return false, 'Choose the FijiCal Lite executable.' end
    if LrFileUtils.exists(path) ~= 'file' then return false, 'FijiCal Lite was not found at the selected location.' end
    if not propertyTable.fijicalOutputFolder or propertyTable.fijicalOutputFolder == '' then
        return false, 'Choose a folder for the rendered images.'
    end
    return true, nil
end

local function uniqueDestination(folder, sourcePath)
    local leaf = LrPathUtils.leafName(sourcePath)
    local stem, extension = string.match(leaf, '^(.*)%.([^%.]+)$')
    stem = stem or leaf
    extension = extension and ('.' .. extension) or ''
    local candidate = LrPathUtils.child(folder, leaf)
    local suffix = 2
    while LrFileUtils.exists(candidate) do
        candidate = LrPathUtils.child(folder, stem .. ' (' .. tostring(suffix) .. ')' .. extension)
        suffix = suffix + 1
    end
    return candidate
end

local exportServiceProvider = {
    hideSections = { 'exportLocation', 'postProcessing' },
    allowFileFormats = { 'JPEG', 'TIFF', 'PNG' },
    canExportVideo = false,
    exportPresetFields = {
        { key = 'fijicalExecutable', default = '' },
        { key = 'fijicalOutputFolder', default = '' },
    },
}

function exportServiceProvider.startDialog(propertyTable)
    if not propertyTable.fijicalExecutable or propertyTable.fijicalExecutable == '' then
        propertyTable.fijicalExecutable = prefs.executablePath or defaultExecutable()
    end
    if not propertyTable.fijicalOutputFolder or propertyTable.fijicalOutputFolder == '' then
        propertyTable.fijicalOutputFolder = prefs.outputFolder or defaultOutputFolder()
    end
end

function exportServiceProvider.sectionsForTopOfDialog(viewFactory, propertyTable)
    local bind = LrView.bind
    return {
        {
            title = 'FijiCal',
            synopsis = bind 'fijicalExecutable',
            viewFactory:column {
                spacing = viewFactory:control_spacing(),
                viewFactory:static_text {
                    title = 'Lightroom will render the selected images, then open the complete batch in Mamanuca.',
                    fill_horizontal = 1,
                    width_in_chars = 52,
                },
                viewFactory:row {
                    spacing = viewFactory:control_spacing(),
                    viewFactory:static_text { title = 'Application:' },
                    viewFactory:edit_field {
                        value = bind 'fijicalExecutable',
                        width_in_chars = 44,
                        immediate = true,
                    },
                    viewFactory:push_button {
                        title = 'Choose…',
                        action = function()
                            local selected = LrDialogs.runOpenPanel {
                                title = 'Choose FijiCal Lite',
                                canChooseFiles = true,
                                canChooseDirectories = false,
                                allowsMultipleSelection = false,
                                fileTypes = { 'exe' },
                            }
                            if selected and selected[1] then propertyTable.fijicalExecutable = selected[1] end
                        end,
                    },
                },
                viewFactory:row {
                    spacing = viewFactory:control_spacing(),
                    viewFactory:static_text { title = 'Export folder:' },
                    viewFactory:edit_field {
                        value = bind 'fijicalOutputFolder',
                        width_in_chars = 44,
                        immediate = true,
                    },
                    viewFactory:push_button {
                        title = 'Choose…',
                        action = function()
                            local selected = LrDialogs.runOpenPanel {
                                title = 'Choose FijiCal export folder',
                                canChooseFiles = false,
                                canChooseDirectories = true,
                                allowsMultipleSelection = false,
                            }
                            if selected and selected[1] then propertyTable.fijicalOutputFolder = selected[1] end
                        end,
                    },
                },
            },
        },
    }
end

function exportServiceProvider.processRenderedPhotos(functionContext, exportContext)
    local propertyTable = exportContext.propertyTable
    local valid, problem = validate(propertyTable)
    if not valid then
        LrDialogs.message('Export to FijiCal', problem, 'critical')
        return
    end

    local executable = executablePath(propertyTable)
    local outputFolder = propertyTable.fijicalOutputFolder
    prefs.executablePath = executable
    prefs.outputFolder = outputFolder
    LrFileUtils.createAllDirectories(outputFolder)
    local progress = exportContext:configureProgress { title = 'Exporting to FijiCal' }
    local rendered = {}
    for _, rendition in exportContext:renditions { stopIfCanceled = true } do
        local success, pathOrMessage = rendition:waitForRender()
        if success then
            local destination = uniqueDestination(outputFolder, pathOrMessage)
            local copied, copyError = LrFileUtils.copy(pathOrMessage, destination)
            if copied then
                table.insert(rendered, destination)
            else
                rendition:renditionIsDone(false, copyError or 'Could not copy the rendered image to the FijiCal export folder.')
            end
        else
            rendition:renditionIsDone(false, pathOrMessage)
        end
    end

    if progress:isCanceled() or #rendered == 0 then return end

    LrShell.openFilesInApp(rendered, executable)
end

return exportServiceProvider
