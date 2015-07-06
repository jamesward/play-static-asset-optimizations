package controllers

import javax.inject.Inject

import play.api.Configuration
import play.api.mvc.{Action, Controller}
import play.api.libs.json.Json

class Application @Inject() (config: Configuration) extends Controller {

  // returns a path that has a version if the assets.version config is set
  // prepends a url if the assets.url config is set
  private def assetUrl(file: String): String = {
    val versionedUrl = routes.Assets.versioned(file).url
    val maybeAssetsUrl = config.getString("assets.url")
    maybeAssetsUrl.fold(versionedUrl)(_ + versionedUrl)
  }

  def index = Action {
    Ok(views.html.index("Hello Play Framework", assetUrl))
  }
  
  def foo = Action {
    Ok(Json.obj("message" -> "hello, foo"))
  }
  
}