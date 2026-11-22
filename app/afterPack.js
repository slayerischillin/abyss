// set the Abyss icon and name inside Abyss.exe without needing Wine
const fs=require('fs'),path=require('path');
exports.default=async ctx=>{if(ctx.electronPlatformName!=='win32')return;const ResEdit=await import('resedit');
  const exe=path.join(ctx.appOutDir,'Abyss.exe'),data=fs.readFileSync(exe);
  const pe=ResEdit.NtExecutable.from(data),res=ResEdit.NtExecutableResource.from(pe);
  const ico=ResEdit.Data.IconFile.from(fs.readFileSync(path.join(__dirname,'icon.ico')));
  const groups=ResEdit.Resource.IconGroupEntry.fromEntries(res.entries);
  ResEdit.Resource.IconGroupEntry.replaceIconsForResource(res.entries,groups.length?groups[0].id:1,groups.length?groups[0].lang:1033,ico.icons.map(i=>i.data));
  const vi=ResEdit.Resource.VersionInfo.fromEntries(res.entries);
  vi.forEach(v=>{v.setStringValues({lang:1033,codepage:1200},{FileDescription:'Abyss',ProductName:'Abyss',InternalName:'Abyss',OriginalFilename:'Abyss.exe',CompanyName:'Abyss'});v.outputToResourceEntries(res.entries)});
  res.outputResource(pe);fs.writeFileSync(exe,Buffer.from(pe.generate()))};
