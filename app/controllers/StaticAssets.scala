package controllers

import play.api.mvc.{Action, Controller}
import play.api.Play.current


object StaticAssets extends Controller {
  
  // drop the version and serve the asset
  def at(path: String, version: String, file: String): Action[_] = {
    Assets.at(path, file)
  }

  // returns a path that has a version if the assets.version config is set
  // prepends a url if the assets.url config is set
  def url(file: String): String = {
    val maybeAssetsVersion = current.configuration.getString("assets.version")
    val maybeVersionedUrl = maybeAssetsVersion.fold(routes.Assets.at(file).url)(routes.StaticAssets.at(_, file).url)

    val maybeAssetsUrl = current.configuration.getString("assets.url")
    maybeAssetsUrl.fold(maybeVersionedUrl)(_ + maybeVersionedUrl)
  }
  
}