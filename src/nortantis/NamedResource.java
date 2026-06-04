package nortantis;

import org.apache.commons.io.FilenameUtils;
import org.json.simple.JSONObject;

import java.io.Serializable;
import java.util.Objects;

@SuppressWarnings("serial")
public class NamedResource implements Serializable
{
	public final String name;
	public final String artPack;

	public NamedResource(String artPack, String fileOrFolderName)
	{
		super();
		this.name = fileOrFolderName;
		assert name != null;
		this.artPack = artPack;
		assert artPack != null;
	}

	@SuppressWarnings("unchecked")
	public JSONObject toJSon()
	{
		JSONObject obj = new JSONObject();
		obj.put("name", name);
		obj.put("artPack", artPack);
		return obj;
	}

	public static NamedResource fromJson(JSONObject obj)
	{
		if (obj == null)
		{
			return null;
		}
		String fileName = (String) obj.get("name");
		String artPack = (String) obj.get("artPack");
		if (fileName == null || artPack == null)
		{
			// A resource is only meaningful with both a name and an art pack. A file that stores either
			// as null (e.g. one saved by a build with assertions disabled where an invalid NamedResource
			// was constructed) would otherwise crash loading on the constructor's assertions. Treat it as
			// "no resource" so the map still opens.
			return null;
		}
		return new NamedResource(artPack, fileName);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(artPack, name);
	}

	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		if (obj == null)
		{
			return false;
		}
		if (getClass() != obj.getClass())
		{
			return false;
		}
		NamedResource other = (NamedResource) obj;
		return Objects.equals(artPack, other.artPack) && Objects.equals(name, other.name);
	}

	@Override
	public String toString()
	{
		return FilenameUtils.getBaseName(name) + " [" + artPack + "]";
	}

}
